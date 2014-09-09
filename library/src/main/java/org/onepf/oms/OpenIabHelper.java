/*
 * Copyright 2012-2014 One Platform Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onepf.oms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.onepf.oms.appstore.AmazonAppstore;
import org.onepf.oms.appstore.GooglePlay;
import org.onepf.oms.appstore.NokiaStore;
import org.onepf.oms.appstore.OpenAppstore;
import org.onepf.oms.appstore.SamsungApps;
import org.onepf.oms.appstore.SamsungAppsBillingService;
import org.onepf.oms.appstore.googleUtils.IabException;
import org.onepf.oms.appstore.googleUtils.IabHelper;
import org.onepf.oms.appstore.googleUtils.IabHelper.OnIabSetupFinishedListener;
import org.onepf.oms.appstore.googleUtils.IabResult;
import org.onepf.oms.appstore.googleUtils.Inventory;
import org.onepf.oms.appstore.googleUtils.Purchase;
import org.onepf.oms.appstore.googleUtils.Security;
import org.onepf.oms.util.CollectionUtils;
import org.onepf.oms.util.Logger;
import org.onepf.oms.util.Utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;

/**
 * @author Boris Minaev, Oleg Orlov, Kirill Rozov
 * @since 16.04.13
 */
public class OpenIabHelper {
    /**
     * Default timeout (in milliseconds) for check inventory in all stores.
     * For generic stores it takes 1.5 - 3sec.
     * <p/>
     * SamsungApps initialization is very time consuming (from 4 to 12 seconds).
     * TODO: Optimize: ~1sec is consumed for check account certification via account activity + ~3sec for actual setup
     */
    private static final int CHECK_INVENTORY_TIMEOUT = 10 * 1000;
    /**
     * Default timeout (in milliseconds) for discover all OpenStores on device.
     */
    private static final int DEFAULT_DISCOVER_TIMEOUT = 5 * 1000;

    private static final String BIND_INTENT = "org.onepf.oms.openappstore.BIND";

    /**
     * Used for all communication with Android services
     */
    private final Context context;
    private final PackageManager packageManager;

    /**
     * Necessary to initialize SamsungApps. For other stuff {@link #context} is used
     */
    private Activity activity;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * selected appstore
     */
    private Appstore mAppstore;

    /**
     * selected appstore billing service
     */
    private AppstoreInAppBillingService mAppstoreBillingService;

    private final Options options;

    public static final int SETUP_RESULT_NOT_STARTED = -1;
    public static final int SETUP_RESULT_SUCCESSFUL = 0;
    public static final int SETUP_RESULT_FAILED = 1;
    public static final int SETUP_DISPOSED = 2;
    public static final int SETUP_IN_PROGRESS = 3;

    @MagicConstant(intValues = {SETUP_DISPOSED, SETUP_IN_PROGRESS,
            SETUP_RESULT_FAILED, SETUP_RESULT_NOT_STARTED, SETUP_RESULT_SUCCESSFUL})
    private int setupState = SETUP_RESULT_NOT_STARTED;

    /**
     * SamsungApps requires {@link #handleActivityResult(int, int, Intent)} but it doesn't
     * work until setup is completed.
     */
    private volatile SamsungApps samsungInSetup;

    // Is an asynchronous operation in progress?
    // (only one at a time can be in progress)
    private boolean mAsyncInProgress = false;

    // (for logging/debugging)
    // if mAsyncInProgress == true, what asynchronous operation is in progress?
    private String mAsyncOperation = "";

    // Item types
    public static final String ITEM_TYPE_INAPP = "inapp";
    public static final String ITEM_TYPE_SUBS = "subs";

    // Billing response codes
    public static final int BILLING_RESPONSE_RESULT_OK = 0;
    public static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3;
    public static final int BILLING_RESPONSE_RESULT_ERROR = 6;

    // Known wrappers
    public static final String NAME_GOOGLE = "com.google.play";
    public static final String NAME_AMAZON = "com.amazon.apps";
    public static final String NAME_SAMSUNG = "com.samsung.apps";
    public static final String NAME_NOKIA = "com.nokia.nstore";

    // Knows open stores
    public static final String NAME_YANDEX = "com.yandex.store";
    public static final String NAME_APPLAND = "Appland";
    public static final String NAME_SLIDEME = "SlideME";
    public static final String NAME_APTOIDE = "cm.aptoide.pt";

    private static interface AppstoreFactory{
        Appstore get();
    }

    private final Map<String, String> appstorePackageMap = new HashMap<String, String>();
    private final Map<String, AppstoreFactory> appstoreFactoryMap = new HashMap<String, AppstoreFactory>();

    {
        // Known packages for open stores
        appstorePackageMap.put("com.yandex.store", NAME_YANDEX);
        appstorePackageMap.put("cm.aptoide.pt", NAME_APTOIDE);


        appstorePackageMap.put("com.google.play", NAME_GOOGLE);
        appstoreFactoryMap.put(NAME_GOOGLE, new AppstoreFactory() {
            @Override
            public Appstore get() {
                final String googleKey = options.getStoreKey(NAME_GOOGLE);
                return new GooglePlay(context, googleKey);
            }
        });

        appstorePackageMap.put("com.amazon.apps", NAME_AMAZON);
        appstoreFactoryMap.put(NAME_AMAZON, new AppstoreFactory() {
            @Override
            public Appstore get() {
                return new AmazonAppstore(context);
            }
        });

        appstorePackageMap.put("com.samsung.apps", NAME_SAMSUNG);
        appstoreFactoryMap.put(NAME_SAMSUNG, new AppstoreFactory() {
            @Override
            public Appstore get() {
                return new SamsungApps(activity, options);
            }
        });

        appstorePackageMap.put("com.nokia.nstore", NAME_NOKIA);
        appstoreFactoryMap.put(NAME_NOKIA, new AppstoreFactory() {
            @Override
            public Appstore get() {
                return new NokiaStore(context);
            }
        });
    }


    /**
     * @param sku       - application inner SKU
     * @param storeSku  - shouldn't duplicate already mapped values
     * @param storeName - @see {@link IOpenAppstore#getAppstoreName()} or {@link #NAME_AMAZON}, {@link #NAME_GOOGLE}
     * @throws java.lang.IllegalArgumentException If one of arguments is empty or null string.
     * @deprecated Use {@link org.onepf.oms.SkuManager#mapSku(String, String, String)}
     * <p/>
     * Map sku and storeSku for particular store.
     * <p/>
     * The best approach is to use SKU that unique in universe like <code>com.companyname.application.item</code>.
     * Such SKU fit most of stores so it doesn't need to be mapped.
     * <p/>
     * If best approach is not applicable use application inner SKU in code (usually it is SKU for Google Play)
     * and map SKU from other stores using this method. OpenIAB will map SKU in both directions,
     * so you can use only your inner SKU
     */
    public static void mapSku(String sku, String storeName, String storeSku) {
        SkuManager.getInstance().mapSku(sku, storeName, storeSku);
    }

    /**
     * @param appstoreName - Name of store.
     * @param sku          - inner SKU
     * @return SKU used in store for specified inner SKU
     * @see org.onepf.oms.SkuManager#mapSku(String, String, String)
     * @deprecated Use {@link org.onepf.oms.SkuManager#getStoreSku(String, String)}
     * <p/>
     * Return previously mapped store SKU for specified inner SKU
     */
    public static String getStoreSku(final String appstoreName, String sku) {
        return SkuManager.getInstance().getStoreSku(appstoreName, sku);
    }

    /**
     * @see org.onepf.oms.SkuManager#mapSku(String, String, String)
     * @deprecated Use {@link org.onepf.oms.SkuManager#getSku(String, String)}
     * <p/>
     * Return mapped application inner SKU using store name and store SKU.
     */
    public static String getSku(final String appstoreName, String storeSku) {
        return SkuManager.getInstance().getSku(appstoreName, storeSku);
    }

    /**
     * @param appstoreName for example {@link OpenIabHelper#NAME_AMAZON}
     * @return list of skus those have mappings for specified appstore
     * @deprecated Use {@link org.onepf.oms.SkuManager#getAllStoreSkus(String)}
     */
    public static List<String> getAllStoreSkus(final String appstoreName) {
        final Collection<String> allStoreSkus =
                SkuManager.getInstance().getAllStoreSkus(appstoreName);
        return allStoreSkus == null ? Collections.<String>emptyList()
                : new ArrayList<String>(allStoreSkus);
    }

    /**
     * @param storeKeys - see {@link Options#storeKeys}
     * @param context   - if you want to support Samsung Apps you must pass an Activity, in other cases any context is acceptable
     * @deprecated Use {@link org.onepf.oms.OpenIabHelper#OpenIabHelper(android.content.Context, org.onepf.oms.OpenIabHelper.Options)}
     * Will be removed in 1.0 release.
     * <p/>
     * <p/>
     * Simple constructor for OpenIabHelper.
     * <p>See {@link OpenIabHelper#OpenIabHelper(Context, Options)} for details
     */
    public OpenIabHelper(Context context, Map<String, String> storeKeys) {
        this(context,
                new Options.Builder()
                        .addStoreKeys(storeKeys)
                        .build()
        );
    }

    /**
     * @param storeKeys       - see {@link org.onepf.oms.OpenIabHelper.Options#getStoreKeys()}
     * @param preferredStores - see {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()}
     * @param context         - if you want to support Samsung Apps you must pass an Activity, in other cases any context is acceptable
     * @deprecated Use {@link org.onepf.oms.OpenIabHelper#OpenIabHelper(android.content.Context, org.onepf.oms.OpenIabHelper.Options)}
     * Will be removed in 1.0 release.
     * <p/>
     * <p/>
     * Simple constructor for OpenIabHelper.
     * <p>See {@link OpenIabHelper#OpenIabHelper(Context, Options)} for details
     */
    public OpenIabHelper(Context context, Map<String, String> storeKeys, String[] preferredStores) {
        this(context,
                new Options.Builder()
                        .addStoreKeys(storeKeys)
                        .addPreferredStoreName(preferredStores)
                        .build()
        );
    }

    /**
     * @param storeKeys       - see {@link org.onepf.oms.OpenIabHelper.Options#getStoreKeys()}
     * @param preferredStores - see {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()}
     * @param availableStores - see {@link org.onepf.oms.OpenIabHelper.Options#getAvailableStores()}
     * @param context         - if you want to support Samsung Apps you must pass an Activity, in other cases any context is acceptable
     * @deprecated Use {@link org.onepf.oms.OpenIabHelper#OpenIabHelper(android.content.Context, org.onepf.oms.OpenIabHelper.Options)}
     * Will be removed in 1.0 release.
     * <p/>
     * Simple constructor for OpenIabHelper.
     * <p>See {@link OpenIabHelper#OpenIabHelper(Context, Options)} for details
     */
    public OpenIabHelper(Context context, Map<String, String> storeKeys, String[] preferredStores, Appstore[] availableStores) {
        this(context,
                new Options.Builder()
                        .addStoreKeys(storeKeys)
                        .addPreferredStoreName(preferredStores)
                        .addAvailableStores(availableStores)
                        .build()
        );
    }

    /**
     * Before start ensure you already have <li>
     * - permission <code>org.onepf.openiab.permission.BILLING</code> in your AndroidManifest.xml<li>
     * - publicKey for store you decided to work with (you can find it in Developer Console of your store)<li>
     * - map SKUs for your store if they differs using {@link #mapSku(String, String, String)}</li>
     * <p/>
     * <p/>
     * You can specify publicKeys for stores (excluding Amazon and SamsungApps those don't use
     * verification based on RSA keys). See {@link Options#storeKeys} for details
     * <p/>
     * By default verification will be performed for receipt from every store. To aviod verification
     * exception OpenIAB doesn't connect to store that key is not specified for
     * <p/>
     * If you don't want to put publicKey in code and verify receipt remotely, you need to set
     * {@link Options#verifyMode} to {@link Options#VERIFY_SKIP}.
     * To make OpenIAB connect even to stores key is not specified for, use {@link Options#VERIFY_ONLY_KNOWN}
     * <p/>
     * {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()} is useful option when you test your app on device with multiple
     * stores installed. Specify store name you want to work with here and it would be selected if you
     * install application using adb.
     *
     * @param options - specify all necessary options
     * @param context - if you want to support Samsung Apps you must pass an Activity, in other cases any context is acceptable
     */
    public OpenIabHelper(Context context, Options options) {
        this.context = context.getApplicationContext();
        packageManager = context.getPackageManager();
        this.options = options;
        if (context instanceof Activity) {
            this.activity = (Activity) context;
        }

        checkOptions();
        Logger.init();
    }

    private Executor setupThreadPoolExecutor;

    /**
     * Discover all available stores and select the best billing service.
     * <p/>
     * Should be called from UI thread
     *
     * @param listener - called when setup is completed
     */
    public void startSetup(final IabHelper.OnIabSetupFinishedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Setup listener must be not null!");
        }
        if (setupState != SETUP_RESULT_NOT_STARTED) {
            throw new IllegalStateException("Couldn't be set up. Current state: " + setupStateToString(setupState));
        }
        Logger.init();
        setupState = SETUP_IN_PROGRESS;
        setupThreadPoolExecutor = Executors.newSingleThreadExecutor();

        final String packageName = context.getPackageName();
        final String packageInstaller = packageManager.getInstallerPackageName(packageName);
        if (TextUtils.isEmpty(packageInstaller)) {
            // Package installer is not set
            setup(listener);
        } else {
            setupForPackage(listener, packageInstaller);
        }
    }

    private void setupForPackage(final IabHelper.OnIabSetupFinishedListener listener, final String packageInstaller) {
        if (!Utils.packageInstalled(context, packageInstaller)) {
            // Package installer no longer exist, fallback to default algorithm
            setup(listener);
            return;
        }

        final String appstoreName = appstorePackageMap.get(packageInstaller);
        if (!TextUtils.isEmpty(appstoreName)) {
            final Appstore appstore;
            // Package installer is a known appstore
            if (!CollectionUtils.isEmpty(options.getAvailableStores())) {
                // Check available stores
                appstore = options.getAvailableStoreWithName(appstoreName);
            } else {
                // Check all available wrappers
                appstore = appstoreFactoryMap.get(appstoreName).get();
            }
            if (appstore != null) {
                checkBillingAndFinish(listener, appstore);
            } else {
                finishSetupNoStore(listener);
            }
            // Either we found appstore or not, we know it was a wrapper
            // Our work here is done
            return;
        }

        // Package installer is unknown
        // Look for package installer among available open stores
        Intent bindServiceIntent = null;
        for (final ServiceInfo serviceInfo : queryOpenStoreServices()) {
            final String servicePackage = serviceInfo.packageName;
            if (TextUtils.equals(packageInstaller, servicePackage)) {
                bindServiceIntent = getBindServiceIntent(serviceInfo);
                break;
            }
        }
        if (bindServiceIntent != null) {
            // We've got an open store as package installer
            final ServiceConnection serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(final ComponentName name, final IBinder service) {
                    checkBillingAndFinish(listener, name, service, this);
                }

                @Override
                public void onServiceDisconnected(final ComponentName name) {}
            };
            if (context.bindService(bindServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                // We fount suitable open store, wait for service to connect
                return;
            }
        }

        // Appstore is unknown to this library
        finishSetupNoStore(listener);
    }

    private void setup(final IabHelper.OnIabSetupFinishedListener listener) {
        // Look for appropriate open store
        final List<ServiceInfo> serviceInfos = queryOpenStoreServices();
        if (serviceInfos.isEmpty()) {
            // No open stores available, fall back to wrappers
            checkWrappersAndFinish(listener);
            return;
        }
        // TODO check preferred stores
        // TODO handle check inventory
        final Queue<Intent> bindServiceIntents = new LinkedList<Intent>();
        for (final ServiceInfo serviceInfo : serviceInfos) {
            bindServiceIntents.add(getBindServiceIntent(serviceInfo));
        }

        checkOpenStoresThenWrappers(listener, bindServiceIntents);
    }

    private void checkOpenStoresThenWrappers(final IabHelper.OnIabSetupFinishedListener listener,
                                             final Queue<Intent> bindServiceIntents) {
        final ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName name, final IBinder service) {
                final ServiceConnection serviceConnection = this;
                setupThreadPoolExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Runnable finishRunnable = null;
                        Appstore openStore = null;
                        try {
                            openStore = checkOpenStoreBilling(name, service, serviceConnection);
                        } catch (RemoteException ignore) {}
                        if (openStore != null) {
                            // Found open store
                            final Appstore appstore = openStore;
                            final IabResult iabResult = new IabResult(BILLING_RESPONSE_RESULT_OK, "Setup ok");
                            finishRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    finishSetup(listener, iabResult, appstore);
                                }
                            };
                        }
                        if (finishRunnable == null) {
                            finishRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    // Check another store
                                    checkOpenStoresThenWrappers(listener, bindServiceIntents);
                                }
                            };
                        }
                        handler.post(finishRunnable);
                    }
                });
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {}
        };

        while (!bindServiceIntents.isEmpty()) {
            if (context.bindService(bindServiceIntents.poll(), serviceConnection, Context.BIND_AUTO_CREATE)) {
                return;
            }
        }

        // No suitable open store found, fall back to wrappers
        checkWrappersAndFinish(listener);
    }

    private void checkWrappersAndFinish(final IabHelper.OnIabSetupFinishedListener listener) {
        // List of wrappers to check
        final Set<Appstore> appstores = new LinkedHashSet<Appstore>();
        for (final String name : options.getPreferredStoreNames()) {
            // Add available stored according to preferred stores priority
            final Appstore appstore = options.getAvailableStoreWithName(name);
            if (appstore != null) {
                appstores.add(appstore);
            }
        }
        final List<Appstore> availableStores;
        if (!CollectionUtils.isEmpty(availableStores = options.getAvailableStores())) {
            // Use only stores specified explicitly
            appstores.addAll(availableStores);
        } else {
            // Add all available wrappers
            for (final String name : appstoreFactoryMap.keySet()) {
                // No need to instantiate appstore if it's already added
                boolean alreadyAdded = false;
                for (final Appstore appstore : appstores) {
                    if (TextUtils.equals(appstore.getAppstoreName(), name)) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) {
                    appstores.add(appstoreFactoryMap.get(name).get());
                }
            }
        }
        checkBillingAndFinish(listener, appstores);
    }

    private List<ServiceInfo> queryOpenStoreServices() {
        final Intent intentAppstoreServices = new Intent(BIND_INTENT);
        final PackageManager packageManager = context.getPackageManager();
        final List<ResolveInfo> resolveInfos = packageManager.queryIntentServices(intentAppstoreServices, 0);
        final List<ServiceInfo> serviceInfos = new ArrayList<ServiceInfo>();
        for (final ResolveInfo resolveInfo : resolveInfos) {
            serviceInfos.add(resolveInfo.serviceInfo);
        }
        return (List<ServiceInfo>) Collections.unmodifiableCollection(serviceInfos);
    }

    @Nullable
    private OpenAppstore getOpenAppstore(final ComponentName name,
                                         final ServiceConnection serviceConnection,
                                         final IOpenAppstore openAppstoreService)
            throws RemoteException {
        final String appstoreName = openAppstoreService.getAppstoreName();
        if (TextUtils.isEmpty(appstoreName)) { // no name - no service
            Logger.e("discoverOpenStores() Appstore doesn't have name. Skipped. ComponentName: ", name);
            return null;
        }

        final Intent billingIntent = openAppstoreService.getBillingServiceIntent();
        if (billingIntent == null) {
            Logger.d("discoverOpenStores(): billing is not supported by store: ", name);
            return null;
        }

        // TODO Do something about verifyMode
//      if ((options.verifyMode == Options.VERIFY_EVERYTHING) && !options.hasStoreKey(appstoreName)) {
//            don't connect to OpenStore if no key provided and verification is strict
//            Logger.e("discoverOpenStores() verification is required but publicKey is not provided: ", name);
//      }
//        String publicKey = options.getStoreKey(appstoreName);
//        if (options.verifyMode == Options.VERIFY_SKIP) publicKey = null;

        final OpenAppstore openAppstore = new OpenAppstore(context, appstoreName, openAppstoreService, billingIntent, null, serviceConnection);
        openAppstore.componentName = name;
        return openAppstore;
    }

    private Intent getBindServiceIntent(final ServiceInfo serviceInfo) {
        final Intent bindServiceIntent = new Intent(BIND_INTENT);
        bindServiceIntent.setClassName(serviceInfo.packageName, serviceInfo.name);
        return bindServiceIntent;
    }

    private Appstore checkOpenStoreBilling(final ComponentName name,
                                           final IBinder service,
                                           final ServiceConnection serviceConnection)
            throws RemoteException {
        if (Utils.uiThread()) {
            throw new IllegalStateException("Must not be called from UI thread");
        }
        final IOpenAppstore openAppstoreService = IOpenAppstore.Stub.asInterface(service);
        // Check billing and create Appstore object for open store
        Appstore openAppstore = null;
        if (openAppstoreService.isBillingAvailable(context.getPackageName())) {
            openAppstore = getOpenAppstore(name, serviceConnection, openAppstoreService);
        }
        if (openAppstore != null && versionOk(openAppstore)) {
            return openAppstore;
        }
        // Billing is unavailable for this open store
        return null;
    }

    private void checkBillingAndFinish(@NotNull final IabHelper.OnIabSetupFinishedListener listener,
                                       final ComponentName name,
                                       final IBinder service,
                                       final ServiceConnection serviceConnection){
        setupThreadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Appstore openAppstore = null;
                int iabResultResponse;
                String iabResultMessage;
                try {
                    openAppstore = checkOpenStoreBilling(name, service, serviceConnection);
                    if (openAppstore != null) {
                        iabResultResponse = BILLING_RESPONSE_RESULT_OK;
                        iabResultMessage = "Setup ok";
                    } else {
                        iabResultResponse = BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE;
                        iabResultMessage = "Billing unavailable for open store";
                    }
                } catch (RemoteException e) {
                    iabResultResponse = BILLING_RESPONSE_RESULT_ERROR;
                    iabResultMessage = "Error occurred during billing setup";
                }
                final IabResult iabResult = new IabResult(iabResultResponse, iabResultMessage);
                final Appstore appstore = openAppstore;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishSetup(listener, iabResult, appstore);
                    }
                });
            }
        });
    }

    private void checkBillingAndFinish(@NotNull final IabHelper.OnIabSetupFinishedListener listener,
                                       @NotNull final Appstore appstore) {
        checkBillingAndFinish(listener, Arrays.asList(appstore));
    }

    private void checkBillingAndFinish(@NotNull final IabHelper.OnIabSetupFinishedListener listener,
                                       @NotNull final Collection<Appstore> appstores) {
        final String packageName = context.getPackageName();
        if (appstores.isEmpty()) {
            finishSetupNoStore(listener);
            return;
        }
        setupThreadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Runnable finishRunnable = null;
                for (final Appstore appstore : appstores) {
                    final IabResult iabResult;
                    if (appstore.isBillingAvailable(packageName) && versionOk(appstore)) {
                        iabResult = new IabResult(BILLING_RESPONSE_RESULT_OK, "Setup ok");
                        finishRunnable = new Runnable() {
                            @Override
                            public void run() {
                                finishSetup(listener, iabResult, appstore);
                            }
                        };
                        break;
                    }
                }
                if (finishRunnable == null) {
                    finishRunnable = new Runnable() {
                        @Override
                        public void run() {
                            finishSetupNoStore(listener);
                        }
                    };
                }
                handler.post(finishRunnable);
            }
        });
    }

    private boolean versionOk(@NotNull final Appstore appstore) {
        final String packageName = context.getPackageName();
        int versionCode = Appstore.PACKAGE_VERSION_UNDEFINED;
        try {
            versionCode = context.getPackageManager().getPackageInfo(packageName, 0).versionCode;
        } catch (NameNotFoundException ignore) {}
        return appstore.getPackageVersion(packageName) >= versionCode;
    }

    private void finishSetupNoStore(@NotNull final IabHelper.OnIabSetupFinishedListener listener) {
        final IabResult iabResult = new IabResult(BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE, "No suitable appstore was found");
        finishSetup(listener, iabResult, null);
    }

    private void finishSetup(@NotNull final IabHelper.OnIabSetupFinishedListener listener,
                             @NotNull final IabResult iabResult,
                             @Nullable final Appstore appstore) {
        if (!Utils.uiThread()) {
            throw new IllegalStateException("Must be called from UI thread.");
        }
        if (setupState != SETUP_IN_PROGRESS) {
            throw new IllegalStateException("Setup is not started or already finished.");
        }

        final boolean setUpSuccessful = iabResult.isSuccess();
        setupState = setUpSuccessful ? SETUP_RESULT_SUCCESSFUL : SETUP_RESULT_FAILED;
        setupThreadPoolExecutor = null;
        if (setUpSuccessful) {
            if (appstore == null) {
                throw new IllegalStateException("Appstore can't be null if setup is successful");
            }
            mAppstore = appstore;
            mAppstoreBillingService = appstore.getInAppBillingService();
        }
        Logger.dWithTimeFromUp("finishSetup() === SETUP DONE === result: ", iabResult, " Appstore: ", appstore);
        listener.onIabSetupFinished(iabResult);
    }

    @MagicConstant(intValues = {SETUP_DISPOSED, SETUP_IN_PROGRESS,
            SETUP_RESULT_FAILED, SETUP_RESULT_NOT_STARTED, SETUP_RESULT_SUCCESSFUL})
    public int getSetupState() {
        return setupState;
    }

    /**
     * Must be called after setup is finished. See {@link org.onepf.oms.OpenIabHelper#startSetup(org.onepf.oms.appstore.googleUtils.IabHelper.OnIabSetupFinishedListener)}
     *
     * @return <code>null</code> if no appstore connected, otherwise name of Appstore OpenIAB has connected to.
     */
    public synchronized String getConnectedAppstoreName() {
        if (mAppstore == null) return null;
        return mAppstore.getAppstoreName();
    }

    /**
     * Check options are valid
     */
    public void checkOptions() {
        checkSamsung();
        checkNokia();
        // TODO handle verify
        // check publicKeys. Must be not null and valid
        if (options.verifyMode != Options.VERIFY_SKIP && options.storeKeys != null) {
            for (Entry<String, String> entry : options.storeKeys.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("Null publicKey for store: " + entry.getKey() + ", key: " + entry.getValue());
                }

                try {
                    Security.generatePublicKey(entry.getValue());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid publicKey for store: "
                            + entry.getKey() + ", key: " + entry.getValue(), e);
                }
            }
        }
    }

    private void checkNokia() {
        final boolean hasPermission = Utils.hasRequestedPermission(context, NokiaStore.NOKIA_BILLING_PERMISSION);
        Logger.d("checkNokia() has permission : ", hasPermission);
        if (hasPermission) {
            return;
        }
        if (options.getAvailableStoreWithName(NAME_NOKIA) != null
                || options.hasPreferredStoreName(NAME_NOKIA)) {
            throw new IllegalStateException("Nokia permission \"" +
                    NokiaStore.NOKIA_BILLING_PERMISSION + "\" NOT REQUESTED");
        }
        Logger.d("checkNokia() ignoring Nokia wrapper");
        appstoreFactoryMap.remove(NAME_NOKIA);
    }

    private void checkSamsung() {
        Logger.d("checkSamsung() activity is : ", activity);
        if (activity != null) {
            return;
        }
        if (options.getAvailableStoreWithName(NAME_SAMSUNG) != null
                || options.hasPreferredStoreName(NAME_SAMSUNG)) {
            // Unfortunately, SamsungApps requires to launch their own "Certification Activity"
            // in order to connect to billing service. So it's also needed for OpenIAB.
            //
            // Intance of Activity needs to be passed to OpenIAB constructor to launch
            // Samsung Cerfitication Activity.
            // Activity also need to pass activityResult to OpenIABHelper.handleActivityResult()
            throw new IllegalArgumentException("You must supply Activity object as context in order to use " + NAME_SAMSUNG + " store");
        }
        Logger.d("checkSamsung() ignoring Samsung wrapper");
        appstoreFactoryMap.remove(NAME_SAMSUNG);
    }


    //todo move to Utils
    private static void formatComponentStatus(String message, StringBuilder messageBuilder) {
        if (messageBuilder.length() > 0) {
            messageBuilder.append('\n');
        }
        messageBuilder.append(message);
    }

    /**
     * Connects to Billing Service of each store. Request list of user purchases (inventory)
     *
     * @param availableStores - list of stores to check
     * @return list of stores with non-empty inventory
     * @see org.onepf.oms.OpenIabHelper#CHECK_INVENTORY_TIMEOUT
     */
    protected List<Appstore> checkInventory(final List<Appstore> availableStores) {
        String packageName = context.getPackageName();
        // candidates:
        Map<String, Appstore> candidates = new HashMap<String, Appstore>();
        for (Appstore appstore : availableStores) {
            if (appstore.isBillingAvailable(packageName)) {
                candidates.put(appstore.getAppstoreName(), appstore);
            }
        }
        Logger.dWithTimeFromUp(candidates.size(), " inventory candidates");
        final List<Appstore> equippedStores = Collections.synchronizedList(new ArrayList<Appstore>());
        final CountDownLatch storeRemains = new CountDownLatch(candidates.size());
        // for every appstore: connect to billing service and check inventory 
        for (Map.Entry<String, Appstore> entry : candidates.entrySet()) {
            final Appstore appstore = entry.getValue();
            final AppstoreInAppBillingService billingService = entry.getValue().getInAppBillingService();
            billingService.startSetup(new OnIabSetupFinishedListener() {
                public void onIabSetupFinished(IabResult result) {
                    Logger.dWithTimeFromUp("billing set ", appstore.getAppstoreName());
                    if (result.isFailure()) {
                        storeRemains.countDown();
                        return;
                    }
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                Inventory inventory = billingService.queryInventory(false, null, null);
                                if (!inventory.getAllPurchases().isEmpty()) {
                                    equippedStores.add(appstore);
                                }
                                Logger.dWithTimeFromUp("inventoryCheck() in ",
                                        appstore.getAppstoreName(), " found: ",
                                        inventory.getAllPurchases().size(), " purchases");
                            } catch (IabException e) {
                                Logger.e("inventoryCheck() failed for ", appstore.getAppstoreName());
                            }
                            storeRemains.countDown();
                        }
                    }, "inv-check[" + appstore.getAppstoreName() + ']').start();
                }
            });
        }
        try {
            storeRemains.await();
            Logger.dWithTimeFromUp("inventory check done");
        } catch (InterruptedException e) {
            Logger.e(e, "selectBillingService()  inventory check is failed. candidates: ", candidates.size()
                    , ", inventory remains: ", storeRemains.getCount());
        }
        return equippedStores;
    }

    public void dispose() {
        Logger.d("Disposing.");
        if (mAppstoreBillingService != null) {
            mAppstoreBillingService.dispose();
        }
        mAppstore = null;
        mAppstoreBillingService = null;
        activity = null;
        setupState = SETUP_DISPOSED;
    }

    public boolean subscriptionsSupported() {
        checkSetupDone("subscriptionsSupported");
        return mAppstoreBillingService.subscriptionsSupported();
    }

    public void launchPurchaseFlow(Activity act, String sku, int requestCode, IabHelper.OnIabPurchaseFinishedListener listener) {
        launchPurchaseFlow(act, sku, requestCode, listener, "");
    }

    public void launchPurchaseFlow(Activity act, String sku, int requestCode,
                                   IabHelper.OnIabPurchaseFinishedListener listener, String extraData) {
        launchPurchaseFlow(act, sku, ITEM_TYPE_INAPP, requestCode, listener, extraData);
    }

    public void launchSubscriptionPurchaseFlow(Activity act, String sku, int requestCode,
                                               IabHelper.OnIabPurchaseFinishedListener listener) {
        launchSubscriptionPurchaseFlow(act, sku, requestCode, listener, "");
    }

    public void launchSubscriptionPurchaseFlow(Activity act, String sku, int requestCode,
                                               IabHelper.OnIabPurchaseFinishedListener listener, String extraData) {
        launchPurchaseFlow(act, sku, ITEM_TYPE_SUBS, requestCode, listener, extraData);
    }

    public void launchPurchaseFlow(Activity act, String sku, String itemType, int requestCode,
                                   IabHelper.OnIabPurchaseFinishedListener listener, String extraData) {
        checkSetupDone("launchPurchaseFlow");
        mAppstoreBillingService.launchPurchaseFlow(act,
                SkuManager.getInstance().getStoreSku(mAppstore.getAppstoreName(), sku),
                itemType,
                requestCode,
                listener,
                extraData);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        Logger.dWithTimeFromUp("handleActivityResult() requestCode: ", requestCode, " resultCode: ", resultCode, " data: ", data);
        if (requestCode == options.samsungCertificationRequestCode && samsungInSetup != null) {
            return samsungInSetup.getInAppBillingService().handleActivityResult(requestCode, resultCode, data);
        }
        if (setupState != SETUP_RESULT_SUCCESSFUL) {
            Logger.d("handleActivityResult() setup is not done. requestCode: ", requestCode, " resultCode: ", resultCode, " data: ", data);
            return false;
        }
        return mAppstoreBillingService.handleActivityResult(requestCode, resultCode, data);
    }

    /**
     * See {@link #queryInventory(boolean, List, List)} for details
     */
    public Inventory queryInventory(boolean querySkuDetails, List<String> moreSkus) throws IabException {
        return queryInventory(querySkuDetails, moreSkus, null);
    }

    /**
     * Queries the inventory. This will query all owned items from the server, as well as
     * information on additional skus, if specified. This method may block or take long to execute.
     * Do not call from a UI thread. For that, use the non-blocking version {@link #queryInventoryAsync(boolean, java.util.List, java.util.List, org.onepf.oms.appstore.googleUtils.IabHelper.QueryInventoryFinishedListener)}.
     *
     * @param querySkuDetails if true, SKU details (price, description, etc) will be queried as well
     *                        as purchase information.
     * @param moreItemSkus    additional PRODUCT skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @param moreSubsSkus    additional SUBSCRIPTIONS skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @throws IabException if a problem occurs while refreshing the inventory.
     */
    public Inventory queryInventory(boolean querySkuDetails, List<String> moreItemSkus, List<String> moreSubsSkus) throws IabException {
        checkSetupDone("queryInventory");

        List<String> moreItemStoreSkus;
        final SkuManager skuManager = SkuManager.getInstance();
        if (moreItemSkus != null) {
            moreItemStoreSkus = new ArrayList<String>(moreItemSkus.size());
            for (String sku : moreItemSkus) {
                moreItemStoreSkus.add(skuManager.getStoreSku(mAppstore.getAppstoreName(), sku));
            }
        } else {
            moreItemStoreSkus = null;
        }

        List<String> moreSubsStoreSkus;
        if (moreSubsSkus != null) {
            moreSubsStoreSkus = new ArrayList<String>(moreSubsSkus.size());
            for (String sku : moreSubsSkus) {
                moreSubsStoreSkus.add(skuManager.getStoreSku(mAppstore.getAppstoreName(), sku));
            }
        } else {
            moreSubsStoreSkus = null;
        }
        return mAppstoreBillingService.queryInventory(querySkuDetails, moreItemStoreSkus, moreSubsStoreSkus);
    }

    /**
     * Queries the inventory. This will query all owned items from the server, as well as
     * information on additional skus, if specified. This method may block or take long to execute.
     *
     * @param querySkuDetails if true, SKU details (price, description, etc) will be queried as well
     *                        as purchase information.
     * @param moreItemSkus    additional PRODUCT skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @param moreSubsSkus    additional SUBSCRIPTIONS skus to query information on, regardless of ownership.
     *                        Ignored if null or if querySkuDetails is false.
     * @throws IabException if a problem occurs while refreshing the inventory.
     */
    public void queryInventoryAsync(final boolean querySkuDetails, final List<String> moreItemSkus, final List<String> moreSubsSkus, final IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventory");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null");
        }
        flagStartAsync("refresh inventory");
        (new Thread(new Runnable() {
            public void run() {
                IabResult result = new IabResult(BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.");
                Inventory inv = null;
                try {
                    inv = queryInventory(querySkuDetails, moreItemSkus, moreSubsSkus);
                } catch (IabException ex) {
                    result = ex.getResult();
                }

                flagEndAsync();

                final IabResult result_f = result;
                final Inventory inv_f = inv;
                if (setupState != SETUP_DISPOSED) {
                    handler.post(new Runnable() {
                        public void run() {
                            listener.onQueryInventoryFinished(result_f, inv_f);
                        }
                    });
                }
            }
        })).start();
    }

    /**
     * For details see {@link org.onepf.oms.OpenIabHelper#queryInventoryAsync(boolean, java.util.List, java.util.List, org.onepf.oms.appstore.googleUtils.IabHelper.QueryInventoryFinishedListener)}
     */
    public void queryInventoryAsync(final boolean querySkuDetails, final List<String> moreSkus, final IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventoryAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null!");
        }
        queryInventoryAsync(querySkuDetails, moreSkus, null, listener);
    }

    /**
     * For details see {@link org.onepf.oms.OpenIabHelper#queryInventoryAsync(boolean, java.util.List, java.util.List, org.onepf.oms.appstore.googleUtils.IabHelper.QueryInventoryFinishedListener)}
     */
    public void queryInventoryAsync(IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventoryAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null!");
        }
        queryInventoryAsync(true, null, listener);
    }

    /**
     * For details see {@link org.onepf.oms.OpenIabHelper#queryInventoryAsync(boolean, java.util.List, java.util.List, org.onepf.oms.appstore.googleUtils.IabHelper.QueryInventoryFinishedListener)}
     */
    public void queryInventoryAsync(boolean querySkuDetails, IabHelper.QueryInventoryFinishedListener listener) {
        checkSetupDone("queryInventoryAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Inventory listener must be not null!");
        }
        queryInventoryAsync(querySkuDetails, null, listener);
    }

    public void consume(Purchase itemInfo) throws IabException {
        checkSetupDone("consume");
        Purchase purchaseStoreSku = (Purchase) itemInfo.clone(); // TODO: use Purchase.getStoreSku()
        purchaseStoreSku.setSku(SkuManager.getInstance().getStoreSku(mAppstore.getAppstoreName(), itemInfo.getSku()));
        mAppstoreBillingService.consume(purchaseStoreSku);
    }

    public void consumeAsync(Purchase purchase, IabHelper.OnConsumeFinishedListener listener) {
        checkSetupDone("consumeAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Consume listener must be not null!");
        }
        List<Purchase> purchases = new ArrayList<Purchase>();
        purchases.add(purchase);
        consumeAsyncInternal(purchases, listener, null);
    }

    public void consumeAsync(List<Purchase> purchases, IabHelper.OnConsumeMultiFinishedListener listener) {
        checkSetupDone("consumeAsync");
        if (listener == null) {
            throw new IllegalArgumentException("Consume listener must be not null!");
        }
        consumeAsyncInternal(purchases, null, listener);
    }

    void consumeAsyncInternal(final List<Purchase> purchases,
                              final IabHelper.OnConsumeFinishedListener singleListener,
                              final IabHelper.OnConsumeMultiFinishedListener multiListener) {
        checkSetupDone("consume");
        flagStartAsync("consume");
        (new Thread(new Runnable() {
            public void run() {
                final List<IabResult> results = new ArrayList<IabResult>();
                for (Purchase purchase : purchases) {
                    try {
                        consume(purchase);
                        results.add(new IabResult(BILLING_RESPONSE_RESULT_OK, "Successful consume of sku " + purchase.getSku()));
                    } catch (IabException ex) {
                        results.add(ex.getResult());
                    }
                }

                flagEndAsync();
                if (setupState != SETUP_DISPOSED && singleListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            singleListener.onConsumeFinished(purchases.get(0), results.get(0));
                        }
                    });
                }
                if (setupState != SETUP_DISPOSED && multiListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            multiListener.onConsumeMultiFinished(purchases, results);
                        }
                    });
                }
            }
        })).start();
    }

    // Checks that setup was done; if not, throws an exception.
    void checkSetupDone(String operation) {
        String stateToString = setupStateToString(setupState);
        if (setupState != SETUP_RESULT_SUCCESSFUL) {
            Logger.e("Illegal state for operation (", operation, "): ", stateToString);
            throw new IllegalStateException(stateToString + " Can't perform operation: " + operation);
        }
    }

    void flagStartAsync(String operation) {
        // TODO: why can't be called consume and queryInventory at the same time?
//        if (mAsyncInProgress) {
//            throw new IllegalStateException("Can't start async operation (" +
//                    operation + ") because another async operation(" + mAsyncOperation + ") is in progress.");
//        }
        mAsyncOperation = operation;
        mAsyncInProgress = true;
        Logger.d("Starting async operation: ", operation);
    }

    void flagEndAsync() {
        Logger.d("Ending async operation: ", mAsyncOperation);
        mAsyncOperation = "";
        mAsyncInProgress = false;
    }

    private static String setupStateToString(int setupState) {
        String state;
        if (setupState == SETUP_RESULT_NOT_STARTED) {
            state = " IAB helper is not set up.";
        } else if (setupState == SETUP_DISPOSED) {
            state = "IAB helper was disposed of.";
        } else if (setupState == SETUP_RESULT_SUCCESSFUL) {
            state = "IAB helper is set up.";
        } else if (setupState == SETUP_RESULT_FAILED) {
            state = "IAB helper setup failed.";
        } else {
            throw new IllegalStateException("Wrong setup state: " + setupState);
        }
        return state;
    }

    /**
     * @deprecated Use {@link org.onepf.oms.util.Logger#isLoggable()}
     * <p/>
     * Will be removed in version 1.0.
     */
    public static boolean isDebugLog() {
        return Logger.isLoggable();
    }

    /**
     * @deprecated Use {@link org.onepf.oms.util.Logger#setLoggable(boolean)}
     * <p/>
     * Will be removed in version 1.0.
     */
    public static void enableDebugLogging(boolean enabled) {
        Logger.setLoggable(enabled);
    }

    /**
     * @deprecated Use {@link org.onepf.oms.util.Logger#setLoggable(boolean)}. Param 'tag' no effect.
     * <p/>
     * Will be removed in version 1.0.
     */
    public static void enableDebuglLogging(boolean enabled, String tag) {
        Logger.setLoggable(enabled);
    }

    public static boolean isPackageInstaller(Context appContext, String installer) {
        String installerPackageName = appContext.getPackageManager().getInstallerPackageName(appContext.getPackageName());
        return installerPackageName != null && installerPackageName.equals(installer);
    }

    public interface OnInitListener {
        void onInitFinished();
    }

    public interface OnOpenIabHelperInitFinished {
        void onOpenIabHelperInitFinished();
    }

    /**
     * All options of OpenIAB can be found here.
     * Create instance of this class via {@link org.onepf.oms.OpenIabHelper.Options.Builder}.
     * <p/>
     * TODO: consider to use cloned instance of Options in OpenIABHelper
     */
    public static class Options {

        /**
         * Verify signatures in any store.
         * <p/>
         * By default in Google's IabHelper. Throws exception if key is not available or invalid.
         * To prevent crashes OpenIAB wouldn't connect to OpenStore if no publicKey provided
         */
        public static final int VERIFY_EVERYTHING = 0;

        /**
         * Don't verify signatures. To perform verification on server-side
         */
        public static final int VERIFY_SKIP = 1;

        /**
         * Verify signatures only if publicKey is available. Otherwise skip verification.
         * <p/>
         * Developer is responsible for verify
         */
        public static final int VERIFY_ONLY_KNOWN = 2;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getAvailableStores()}
         * Will be private since 1.0.
         * <p/>
         * List of stores to be used for store elections. By default GooglePlay, Amazon, SamsungApps and
         * all installed OpenStores are used.
         * <p/>
         * To specify your own list, you need to instantiate Appstore object manually.
         * GooglePlay, Amazon and SamsungApps could be instantiated directly. OpenStore can be discovered
         * using {@link OpenIabHelper#discoverOpenStores(Context, List, Options)}
         * <p/>
         * If you put only your instance of Appstore in this list OpenIAB will use it
         * <p/>
         */
        // TODO discover open stores
        public List<Appstore> availableStores = Collections.emptyList();

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * Used as priority list if store that installed app is not found and there are
         * multiple stores installed on device that supports billing.
         */
        public List<String> preferredStoreNames = Collections.emptyList();

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getDiscoveryTimeout()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * Wait specified amount of ms to find all OpenStores on device
         */
        // TODO remove?
        public int discoveryTimeoutMs = DEFAULT_DISCOVER_TIMEOUT;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#isCheckInventory()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * Check user inventory in every store to select proper store
         * <p/>
         * Will try to connect to each billingService and extract user's purchases.
         * If purchases have been found in the only store that store will be used for further purchases.
         * If purchases have been found in multiple stores only such stores will be used for further elections
         */
        public boolean checkInventory;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getCheckInventoryTimeout()}
         * Will be private since 1.0.
         * <p/>
         * Wait specified amount of ms to check inventory in all stores
         */
        public int checkInventoryTimeoutMs = CHECK_INVENTORY_TIMEOUT;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getVerifyMode()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * OpenIAB could skip receipt verification by publicKey for GooglePlay and OpenStores
         * <p/>
         * Receipt could be verified in {@link org.onepf.oms.appstore.googleUtils.IabHelper.OnIabPurchaseFinishedListener#onIabPurchaseFinished(org.onepf.oms.appstore.googleUtils.IabResult, org.onepf.oms.appstore.googleUtils.Purchase)}
         * using {@link Purchase#getOriginalJson()} and {@link Purchase#getSignature()}
         */
        @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP})
        public int verifyMode = VERIFY_EVERYTHING;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options#getStoreKeys()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * storeKeys is map of [ appstore name -> publicKeyBase64 ]
         * Put keys for all stores you support in this Map and pass it to instantiate {@link OpenIabHelper}
         * <p/>
         * <b>publicKey</b> key is used to verify receipt is created by genuine Appstore using
         * provided signature. It can be found in Developer Console of particular store
         * <p/>
         * <b>name</b> of particular store can be provided by local_store tool if you run it on device.
         * For Google Play OpenIAB uses {@link OpenIabHelper#NAME_GOOGLE}.
         * <p/>
         * <p>Note:
         * AmazonApps and SamsungApps doesn't use RSA keys for receipt verification, so you don't need
         * to specify it
         */
        public Map<String, String> storeKeys = new HashMap<String, String>();

        /**
         * @deprecated Usr {@link org.onepf.oms.OpenIabHelper.Options#getSamsungCertificationRequestCode()}
         * Will be private since 1.0.
         * <p/>
         * <p/>
         * Used for SamsungApps setup. Specify your own value if default one interfere your code.
         * <p>default value is {@link SamsungAppsBillingService#REQUEST_CODE_IS_ACCOUNT_CERTIFICATION}
         */
        public int samsungCertificationRequestCode = SamsungAppsBillingService.REQUEST_CODE_IS_ACCOUNT_CERTIFICATION;

        /**
         * @deprecated Use {@link org.onepf.oms.OpenIabHelper.Options.Builder} instead.
         */
        public Options() {
        }

        private Options(List<Appstore> availableStores,
                        Map<String, String> storeKeys,
                        boolean checkInventory,
                        int checkInventoryTimeout,
                        int discoveryTimeout,
                        @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP}) int verifyMode,
                        List<String> preferredStoreNames,
                        int samsungCertificationRequestCode) {
            this.checkInventory = checkInventory;
            this.checkInventoryTimeoutMs = checkInventoryTimeout;
            this.availableStores = availableStores;
            this.discoveryTimeoutMs = discoveryTimeout;
            this.storeKeys = storeKeys;
            this.preferredStoreNames = preferredStoreNames;
            this.verifyMode = verifyMode;
            this.samsungCertificationRequestCode = samsungCertificationRequestCode;
        }

        /**
         * Used for SamsungApps setup. Specify your own value if default one interfere your code.
         * <p/>
         * default value is {@link org.onepf.oms.appstore.SamsungAppsBillingService#REQUEST_CODE_IS_ACCOUNT_CERTIFICATION}
         */
        public int getSamsungCertificationRequestCode() {
            return samsungCertificationRequestCode;
        }

        /**
         * OpenIAB could skip receipt verification by publicKey for GooglePlay and OpenStores
         * <p/>
         * Receipt could be verified in {@link org.onepf.oms.appstore.googleUtils.IabHelper.OnIabPurchaseFinishedListener#onIabPurchaseFinished(org.onepf.oms.appstore.googleUtils.IabResult, org.onepf.oms.appstore.googleUtils.Purchase)}
         * using {@link org.onepf.oms.appstore.googleUtils.Purchase#getOriginalJson()} and {@link org.onepf.oms.appstore.googleUtils.Purchase#getSignature()}
         */
        @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP})
        public int getVerifyMode() {
            return verifyMode;
        }

        /**
         * Check user inventory in every store to select proper store
         * <p/>
         * Will try to connect to each billingService and extract user's purchases.
         * If purchases have been found in the only store that store will be used for further purchases.
         * If purchases have been found in multiple stores only such stores will be used for further elections
         */
        public boolean isCheckInventory() {
            return checkInventory;
        }

        /**
         * Wait specified amount of ms to check inventory in all stores
         */
        public long getCheckInventoryTimeout() {
            return checkInventoryTimeoutMs;
        }

        /**
         * Wait specified amount of ms to find all OpenStores on device
         */
        public long getDiscoveryTimeout() {
            return discoveryTimeoutMs;
        }

        /**
         * List of stores to be used for store elections. By default GooglePlay, Amazon, SamsungApps and
         * all installed OpenStores are used.
         * <p/>
         * To specify your own list, you need to instantiate Appstore object manually.
         * GooglePlay, Amazon and SamsungApps could be instantiated directly. OpenStore can be discovered
         * using {@link OpenIabHelper#discoverOpenStores(android.content.Context, java.util.List, org.onepf.oms.OpenIabHelper.Options)}
         * <p/>
         * If you put only your instance of Appstore in this list OpenIAB will use it
         * <p/>
         */
        public List<Appstore> getAvailableStores() {
            return availableStores;
        }

        /**
         * Used as priority list if store that installed app is not found and there are
         * multiple stores installed on device that supports billing.
         */
        public List<String> getPreferredStoreNames() {
            return preferredStoreNames;
        }

        /**
         * storeKeys is map of [ appstore name -> publicKeyBase64 ]
         * Put keys for all stores you support in this Map and pass it to createInstance {@link OpenIabHelper}
         * <p/>
         * <b>publicKey</b> key is used to verify receipt is created by genuine Appstore using
         * provided signature. It can be found in Developer Console of particular store
         * <p/>
         * <b>name</b> of particular store can be provided by local_store tool if you run it on device.
         * For Google Play OpenIAB uses {@link OpenIabHelper#NAME_GOOGLE}.
         * <p/>
         * <p>Note:
         * AmazonApps and SamsungApps doesn't use RSA keys for receipt verification, so you don't need
         * to specify it
         */
        @Nullable
        public Map<String, String> getStoreKeys() {
            return storeKeys;
        }

        public Appstore getAvailableStoreWithName(@NotNull String name) {
            if (!CollectionUtils.isEmpty(availableStores)) {
                for (Appstore s : availableStores) {
                    if (name.equals(s.getAppstoreName())) {
                        return s;
                    }
                }
            }
            return null;
        }

        public boolean hasPreferredStoreName(@NotNull final String name) {
            return getPreferredStoreNames().contains(name);
        }

        public boolean hasStoreKey(String storeName) {
            return storeKeys != null && storeKeys.containsKey(storeName);
        }

        public String getStoreKey(String storeName) {
            return storeKeys != null ? storeKeys.get(storeName) : null;
        }

        /**
         * Utility class for create instance of {@link org.onepf.oms.OpenIabHelper.Options}
         */
        public static final class Builder {

            private List<String> preferredStoreNames;
            private Map<String, String> storeKeys;
            private List<Appstore> availableStores;
            private int discoveryTimeout = DEFAULT_DISCOVER_TIMEOUT;
            private int checkInventoryTimeout = CHECK_INVENTORY_TIMEOUT;
            private boolean checkInventory;
            private int samsungCertificationRequestCode
                    = SamsungAppsBillingService.REQUEST_CODE_IS_ACCOUNT_CERTIFICATION;

            @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP})
            private int verifyMode = VERIFY_EVERYTHING;

            /**
             * Add available store to options.
             *
             * @param stores Stores to add.
             * @see org.onepf.oms.OpenIabHelper.Options#getAvailableStores()
             */
            public Builder addAvailableStores(Appstore... stores) {
                if (!CollectionUtils.isEmpty(stores)) {
                    if (this.availableStores == null) {
                        this.availableStores = new ArrayList<Appstore>(stores.length);
                    }
                    Collections.addAll(this.availableStores, stores);
                }
                return this;
            }

            /**
             * Add available store to options.
             *
             * @param stores Stores to add.
             * @see org.onepf.oms.OpenIabHelper.Options#getAvailableStores().
             */
            public Builder addAvailableStores(List<Appstore> stores) {
                if (!CollectionUtils.isEmpty(stores)) {
                    if (this.availableStores == null) {
                        this.availableStores = new ArrayList<Appstore>(stores.size());
                    }
                    this.availableStores.addAll(stores);
                }
                return this;
            }

            /**
             * Set check inventory. By default is true.
             *
             * @see org.onepf.oms.OpenIabHelper.Options#isCheckInventory()
             */
            public Builder setCheckInventory(boolean checkInventory) {
                this.checkInventory = checkInventory;
                return this;
            }

            /**
             * Set discovery timeout. By default 5 sec.
             *
             * @throws java.lang.IllegalArgumentException if timeout is negative value.
             * @see org.onepf.oms.OpenIabHelper.Options#getDiscoveryTimeout()
             */
            public Builder setDiscoveryTimeout(int discoveryTimeout) {
                if (discoveryTimeout < 0) {
                    throw new IllegalArgumentException("Discovery timeout can't be" +
                            " a negative value.");
                }
                this.discoveryTimeout = discoveryTimeout;
                return this;
            }

            /**
             * Set inventory check timeout. By default 10 sec.
             * This value has no effect if {@link org.onepf.oms.OpenIabHelper.Options.Builder#setCheckInventory(boolean)}
             * set to false.
             *
             * @throws java.lang.IllegalArgumentException if timeout is negative value.
             * @see org.onepf.oms.OpenIabHelper.Options#getCheckInventoryTimeout()
             * @see org.onepf.oms.OpenIabHelper.Options.Builder#setCheckInventory(boolean)
             */
            public Builder setCheckInventoryTimeout(int checkInventoryTimeout) {
                if (discoveryTimeout < 0) {
                    throw new IllegalArgumentException("Check inventory timeout can't be" +
                            " a negative value.");
                }
                this.checkInventoryTimeout = checkInventoryTimeout;
                return this;
            }

            /**
             * Get list of added available stores.
             *
             * @return List of available store of null if nothing was add.
             */
            @Nullable
            public List<Appstore> getAvailableStores() {
                return availableStores;
            }

            /**
             * Get map "store name -> public key" of added store keys.
             *
             * @return Map of added store keys or null if nothing was add.
             */
            @Nullable
            public Map<String, String> getStoreKeys() {
                return storeKeys;
            }

            /**
             * Add single store keys to options.
             *
             * @param storeName Name of store.
             * @param publicKey Key of store.
             * @throws java.lang.IllegalArgumentException If value pair (storeName, publicKey) can't be add.
             *                                            It can be when store name empty or null,
             *                                            or store public key is not in base64 decode format.
             * @see org.onepf.oms.OpenIabHelper.Options#getStoreKeys()
             */
            public Builder addStoreKey(String storeName, String publicKey) {
                checkStoreKeyParam(storeName, publicKey);

                if (this.storeKeys == null) {
                    this.storeKeys = new HashMap<String, String>();
                }
                this.storeKeys.put(storeName, publicKey);
                return this;
            }

            private static void checkStoreKeyParam(String storeName, String publicKey) {
                if (TextUtils.isEmpty(storeName)) {
                    throw new IllegalArgumentException(
                            "Store name can't be null or empty value.");
                }

                if (TextUtils.isEmpty(publicKey)) {
                    throw new IllegalArgumentException(
                            "Store public key can't be null or empty value.");
                }

                try {
                    Security.generatePublicKey(publicKey);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            String.format("Invalid publicKey for store: %s, key: %s.",
                                    storeName, publicKey),
                            e);
                }
            }

            /**
             * Set verify mode for store. By default set to {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_EVERYTHING}.
             *
             * @param verifyMode Verify doe for store. Must be on of {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_EVERYTHING},
             *                   {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_SKIP},
             *                   {@link org.onepf.oms.OpenIabHelper.Options#VERIFY_ONLY_KNOWN}.
             * @see org.onepf.oms.OpenIabHelper.Options#getVerifyMode()
             */
            public Builder setVerifyMode(
                    @MagicConstant(intValues = {VERIFY_EVERYTHING, VERIFY_ONLY_KNOWN, VERIFY_SKIP}) int verifyMode) {
                this.verifyMode = verifyMode;
                return this;
            }

            /**
             * Add store keys to options.
             *
             * @param storeKeys Map storeName - store public key.
             * @throws java.lang.IllegalArgumentException If one of item in map can't be add.
             * @see org.onepf.oms.OpenIabHelper.Options.Builder#addStoreKeys(java.util.Map)
             * @see org.onepf.oms.OpenIabHelper.Options#getStoreKeys()
             */
            public Builder addStoreKeys(Map<String, String> storeKeys) {
                if (!CollectionUtils.isEmpty(storeKeys)) {
                    for (Entry<String, String> entry : storeKeys.entrySet()) {
                        checkStoreKeyParam(entry.getKey(), entry.getValue());
                    }

                    if (this.storeKeys == null) {
                        this.storeKeys = new HashMap<String, String>();
                    }

                    this.storeKeys.putAll(storeKeys);
                }
                return this;
            }

            /**
             * Add preferred stores to options. Priority of selection is order in what stores add.
             *
             * @see org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()
             */
            public Builder addPreferredStoreName(String... storeNames) {
                if (!CollectionUtils.isEmpty(storeNames)) {
                    if (this.preferredStoreNames == null) {
                        this.preferredStoreNames = new ArrayList<String>(storeNames.length);
                    }
                    Collections.addAll(this.preferredStoreNames, storeNames);
                }
                return this;
            }

            /**
             * Add preferred stores to options. Priority of selection is order in what stores add.
             *
             * @see org.onepf.oms.OpenIabHelper.Options#getPreferredStoreNames()
             */
            public Builder addPreferredStoreName(List<String> storeNames) {
                if (!CollectionUtils.isEmpty(storeNames)) {
                    if (this.preferredStoreNames == null) {
                        this.preferredStoreNames = new ArrayList<String>(storeNames.size());
                    }
                    this.preferredStoreNames.addAll(storeNames);
                }
                return this;
            }

            /**
             * Set request code for samsung certification.
             *
             * @param code Request code. Must be positive value.
             * @throws java.lang.IllegalArgumentException if code negative or zero value.
             * @see org.onepf.oms.OpenIabHelper.Options#getSamsungCertificationRequestCode()
             */
            public Builder setSamsungCertificationRequestCode(int code) {
                if (code < 0) {
                    throw new IllegalArgumentException("Value '" + code +
                            "' can't be request code. Request code must be a positive value.");
                }

                this.samsungCertificationRequestCode = code;
                return this;
            }

            /**
             * @return Create new instance of {@link org.onepf.oms.OpenIabHelper.Options}.
             */
            public Options build() {
                List<Appstore> availableStores = CollectionUtils.isEmpty(this.availableStores) ? Collections.<Appstore>emptyList() :
                        Collections.unmodifiableList(this.availableStores);
                Map<String, String> storeKeys = CollectionUtils.isEmpty(this.storeKeys) ? null :
                        Collections.unmodifiableMap(this.storeKeys);
                List<String> preferredStoreNames = CollectionUtils.isEmpty(this.preferredStoreNames) ? Collections.<String>emptyList() :
                        new ArrayList<String>(this.preferredStoreNames);
                return new Options(
                        availableStores,
                        storeKeys,
                        checkInventory,
                        checkInventoryTimeout,
                        discoveryTimeout,
                        verifyMode,
                        preferredStoreNames,
                        samsungCertificationRequestCode);
            }
        }
    }

}
