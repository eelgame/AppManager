// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandleHidden;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.compat.ActivityManagerCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.misc.AdvancedSearchView;
import io.github.muntashirakon.AppManager.profiles.ProfileMetaManager;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentsBlocker;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.types.PackageChangeReceiver;
import io.github.muntashirakon.AppManager.types.PackageSizeInfo;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.MultithreadedExecutor;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.Utils;

import static io.github.muntashirakon.AppManager.utils.PackageUtils.flagDisabledComponents;
import static io.github.muntashirakon.AppManager.utils.PackageUtils.flagSigningInfo;

public class MainViewModel extends AndroidViewModel {
    private static final Collator sCollator = Collator.getInstance();

    private final PackageManager mPackageManager;
    private final PackageIntentReceiver mPackageObserver;
    private final Handler mHandler;
    @ListOptions.SortOrder
    private int mSortBy;
    private boolean mSortReverse;
    @ListOptions.Filter
    private int mFilterFlags;
    @Nullable
    private String mFilterProfileName;
    private String searchQuery;
    @AdvancedSearchView.SearchType
    private int searchType;
    private final Map<String, int[]> selectedPackages = new HashMap<>();
    private final ArrayList<ApplicationItem> selectedApplicationItems = new ArrayList<>();
    final MultithreadedExecutor executor = MultithreadedExecutor.getNewInstance();

    public MainViewModel(@NonNull Application application) {
        super(application);
        Log.d("MVM", "New instance created");
        mPackageManager = application.getPackageManager();
        mHandler = new Handler(application.getMainLooper());
        mPackageObserver = new PackageIntentReceiver(this);
        mSortBy = AppPref.getInt(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_ORDER_INT);
        mSortReverse = AppPref.getBoolean(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_REVERSE_BOOL);
        mFilterFlags = AppPref.getInt(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_FLAGS_INT);
        mFilterProfileName = AppPref.getString(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_PROFILE_STR);
        if ("".equals(mFilterProfileName)) mFilterProfileName = null;
    }

    @NonNull
    private final MutableLiveData<List<ApplicationItem>> applicationItemsLiveData = new MutableLiveData<>();
    final private List<ApplicationItem> applicationItems = new ArrayList<>();

    public int getApplicationItemCount() {
        return applicationItems.size();
    }

    @NonNull
    public LiveData<List<ApplicationItem>> getApplicationItems() {
        if (applicationItemsLiveData.getValue() == null) {
            loadApplicationItems();
        }
        return applicationItemsLiveData;
    }

    @GuardedBy("applicationItems")
    public ApplicationItem deselect(@NonNull ApplicationItem item) {
        synchronized (applicationItems) {
            int i = applicationItems.indexOf(item);
            if (i == -1) return item;
            selectedPackages.remove(item.packageName);
            selectedApplicationItems.remove(item);
            item.isSelected = false;
            applicationItems.set(i, item);
            return item;
        }
    }

    @GuardedBy("applicationItems")
    public ApplicationItem select(@NonNull ApplicationItem item) {
        synchronized (applicationItems) {
            int i = applicationItems.indexOf(item);
            if (i == -1) return item;
            selectedPackages.put(item.packageName, item.userHandles);
            item.isSelected = true;
            applicationItems.set(i, item);
            int selIndex = selectedApplicationItems.indexOf(item);
            if (selIndex >= 0) {
                selectedApplicationItems.set(selIndex, item);
            } else {
                selectedApplicationItems.add(item);
            }
            return item;
        }
    }

    public void cancelSelection() {
        synchronized (applicationItems) {
            for (ApplicationItem item : selectedApplicationItems) {
                int i = applicationItems.indexOf(item);
                if (i != -1) {
                    applicationItems.get(i).isSelected = false;
                }
            }
            selectedApplicationItems.clear();
            selectedPackages.clear();
        }
    }

    @Nullable
    public ApplicationItem getLastSelectedPackage() {
        if (selectedApplicationItems.size() > 0) {
            return selectedApplicationItems.get(selectedApplicationItems.size() - 1);
        } else return null;
    }

    public Map<String, int[]> getSelectedPackages() {
        return selectedPackages;
    }

    @NonNull
    public ArrayList<UserPackagePair> getSelectedPackagesWithUsers() {
        ArrayList<UserPackagePair> userPackagePairs = new ArrayList<>();
        int myUserId = UserHandleHidden.myUserId();
        int[] userIds = Users.getUsersIds();
        for (String packageName : selectedPackages.keySet()) {
            int[] userHandles = selectedPackages.get(packageName);
            if (userHandles == null || userHandles.length == 0) {
                // Could be a backup only item
                // Assign current user in it
                userPackagePairs.add(new UserPackagePair(packageName, myUserId));
            } else {
                for (int userHandle : userHandles) {
                    if (!ArrayUtils.contains(userIds, userHandle)) continue;
                    userPackagePairs.add(new UserPackagePair(packageName, userHandle));
                }
            }
        }
        return userPackagePairs;
    }

    public ArrayList<ApplicationItem> getSelectedApplicationItems() {
        return selectedApplicationItems;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String searchQuery, @AdvancedSearchView.SearchType int searchType) {
        this.searchQuery = searchType != AdvancedSearchView.SEARCH_TYPE_REGEX ? searchQuery.toLowerCase(Locale.ROOT) : searchQuery;
        this.searchType = searchType;
        executor.submit(this::filterItemsByFlags);
    }

    public int getSortBy() {
        return mSortBy;
    }

    public void setSortReverse(boolean sortReverse) {
        executor.submit(() -> {
            sortApplicationList(mSortBy, mSortReverse);
            filterItemsByFlags();
        });
        mSortReverse = sortReverse;
        AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_REVERSE_BOOL, mSortReverse);
    }

    public boolean isSortReverse() {
        return mSortReverse;
    }

    public void setSortBy(int sortBy) {
        if (mSortBy != sortBy) {
            executor.submit(() -> {
                sortApplicationList(sortBy, mSortReverse);
                filterItemsByFlags();
            });
        }
        mSortBy = sortBy;
        AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_ORDER_INT, mSortBy);
    }

    public boolean hasFilterFlag(@ListOptions.Filter int flag) {
        return (mFilterFlags & flag) != 0;
    }

    public void addFilterFlag(@ListOptions.Filter int filterFlag) {
        mFilterFlags |= filterFlag;
        AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_FLAGS_INT, mFilterFlags);
        executor.submit(this::filterItemsByFlags);
    }

    public void removeFilterFlag(@ListOptions.Filter int filterFlag) {
        mFilterFlags &= ~filterFlag;
        AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_FLAGS_INT, mFilterFlags);
        executor.submit(this::filterItemsByFlags);
    }

    public void setFilterProfileName(@Nullable String filterProfileName) {
        if (mFilterProfileName == null) {
            if (filterProfileName == null) return;
        } else if (mFilterProfileName.equals(filterProfileName)) return;
        mFilterProfileName = filterProfileName;
        AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_PROFILE_STR, filterProfileName == null ? "" : filterProfileName);
        executor.submit(this::filterItemsByFlags);
    }

    public String getFilterProfileName() {
        return mFilterProfileName;
    }

    @AnyThread
    public void onResume() {
        if ((mFilterFlags & ListOptions.FILTER_RUNNING_APPS) != 0) {
            // Reload filters to get running apps again
            executor.submit(this::filterItemsByFlags);
        }
    }

    @GuardedBy("applicationItems")
    public void loadApplicationItems() {
        executor.submit(() -> {
            List<ApplicationItem> updatedApplicationItems = PackageUtils
                    .getInstalledOrBackedUpApplicationsFromDb(getApplication(), executor, true);
            synchronized (applicationItems) {
                applicationItems.clear();
                applicationItems.addAll(updatedApplicationItems);
                // select apps again
                for (ApplicationItem item : selectedApplicationItems) {
                    select(item);
                }
                sortApplicationList(mSortBy, mSortReverse);
                filterItemsByFlags();
            }
        });
    }

    private void filterItemsByQuery(@NonNull List<ApplicationItem> applicationItems) {
        List<ApplicationItem> filteredApplicationItems;
        if (searchType == AdvancedSearchView.SEARCH_TYPE_REGEX) {
            filteredApplicationItems = AdvancedSearchView.matches(searchQuery, applicationItems,
                    (AdvancedSearchView.ChoicesGenerator<ApplicationItem>) item -> new ArrayList<String>() {{
                        add(item.packageName);
                        add(item.label);
                    }}, AdvancedSearchView.SEARCH_TYPE_REGEX);
            mHandler.post(() -> applicationItemsLiveData.postValue(filteredApplicationItems));
            return;
        }
        // Others
        filteredApplicationItems = new ArrayList<>();
        for (ApplicationItem item : applicationItems) {
            if (AdvancedSearchView.matches(searchQuery, item.packageName.toLowerCase(Locale.ROOT), searchType)) {
                filteredApplicationItems.add(item);
            } else if (searchType == AdvancedSearchView.SEARCH_TYPE_CONTAINS) {
                if (Utils.containsOrHasInitials(searchQuery, item.label)) {
                    filteredApplicationItems.add(item);
                }
            } else if (AdvancedSearchView.matches(searchQuery, item.label.toLowerCase(Locale.ROOT), searchType)) {
                filteredApplicationItems.add(item);
            }
        }
        mHandler.post(() -> applicationItemsLiveData.postValue(filteredApplicationItems));
    }

    @WorkerThread
    @GuardedBy("applicationItems")
    private void filterItemsByFlags() {
        synchronized (applicationItems) {
            List<ApplicationItem> candidateApplicationItems = new ArrayList<>();
            if (mFilterProfileName != null) {
                ProfileMetaManager profileMetaManager = new ProfileMetaManager(mFilterProfileName);
                List<Integer> indexes = new ArrayList<>();
                for (String packageName : profileMetaManager.getProfile().packages) {
                    ApplicationItem item = new ApplicationItem();
                    item.packageName = packageName;
                    int index = applicationItems.indexOf(item);
                    if (index != -1) {
                        indexes.add(index);
                    }
                }
                Collections.sort(indexes);
                for (int index : indexes) {
                    candidateApplicationItems.add(applicationItems.get(index));
                }
            } else candidateApplicationItems.addAll(applicationItems);
            // Other filters
            if (mFilterFlags == ListOptions.FILTER_NO_FILTER) {
                if (!TextUtils.isEmpty(searchQuery)) {
                    filterItemsByQuery(candidateApplicationItems);
                } else {
                    mHandler.post(() -> applicationItemsLiveData.postValue(candidateApplicationItems));
                }
            } else {
                List<ApplicationItem> filteredApplicationItems = new ArrayList<>();
                if ((mFilterFlags & ListOptions.FILTER_RUNNING_APPS) != 0) {
                    loadRunningApps();
                }
                for (ApplicationItem item : candidateApplicationItems) {
                    // Filter user and system apps first (if requested)
                    if ((mFilterFlags & ListOptions.FILTER_USER_APPS) != 0 && !item.isUser) {
                        continue;
                    } else if ((mFilterFlags & ListOptions.FILTER_SYSTEM_APPS) != 0 && item.isUser) {
                        continue;
                    }
                    // Filter installed/uninstalled
                    if ((mFilterFlags & ListOptions.FILTER_INSTALLED_APPS) != 0 && !item.isInstalled) {
                        continue;
                    } else if ((mFilterFlags & ListOptions.FILTER_UNINSTALLED_APPS) != 0 && item.isInstalled) {
                        continue;
                    }
                    // Filter backups
                    if ((mFilterFlags & ListOptions.FILTER_APPS_WITH_BACKUPS) != 0 && item.backup == null) {
                        continue;
                    } else if ((mFilterFlags & ListOptions.FILTER_APPS_WITHOUT_BACKUPS) != 0 && item.backup != null) {
                        continue;
                    }
                    // Filter rests
                    if ((mFilterFlags & ListOptions.FILTER_FROZEN_APPS) != 0 && !item.isDisabled) {
                        continue;
                    } else if ((mFilterFlags & ListOptions.FILTER_APPS_WITH_RULES) != 0 && item.blockedCount <= 0) {
                        continue;
                    } else if ((mFilterFlags & ListOptions.FILTER_APPS_WITH_ACTIVITIES) != 0 && !item.hasActivities) {
                        continue;
                    } else if ((mFilterFlags & ListOptions.FILTER_APPS_WITH_SPLITS) != 0 && !item.hasSplits) {
                        continue;
                    } else if ((mFilterFlags & ListOptions.FILTER_RUNNING_APPS) != 0 && !item.isRunning) {
                        continue;
                    }
                    filteredApplicationItems.add(item);
                }
                if (!TextUtils.isEmpty(searchQuery)) {
                    filterItemsByQuery(filteredApplicationItems);
                } else {
                    mHandler.post(() -> applicationItemsLiveData.postValue(filteredApplicationItems));
                }
            }
        }
    }

    @GuardedBy("applicationItems")
    private void loadRunningApps() {
        synchronized (applicationItems) {
            try {
                List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfoList;
                runningAppProcessInfoList = ActivityManagerCompat.getRunningAppProcesses();
                Set<String> runningPackages = new HashSet<>();
                for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcessInfoList) {
                    Collections.addAll(runningPackages, runningAppProcessInfo.pkgList);
                }
                for (int i = 0; i < applicationItems.size(); ++i) {
                    ApplicationItem applicationItem = applicationItems.get(i);
                    applicationItem.isRunning = applicationItem.isInstalled
                            && runningPackages.contains(applicationItem.packageName);
                    applicationItems.set(i, applicationItem);
                }
            } catch (Throwable th) {
                Log.e("MVM", th);
            }
        }
    }

    @GuardedBy("applicationItems")
    private void sortApplicationList(@ListOptions.SortOrder int sortBy, boolean reverse) {
        synchronized (applicationItems) {
            final boolean isRootEnabled = Ops.isRoot();
            if (sortBy != ListOptions.SORT_BY_APP_LABEL) {
                sortApplicationList(ListOptions.SORT_BY_APP_LABEL, false);
            }
            int mode = reverse ? -1 : 1;
            Collections.sort(applicationItems, (o1, o2) -> {
                switch (sortBy) {
                    case ListOptions.SORT_BY_APP_LABEL:
                        return mode * sCollator.compare(o1.label, o2.label);
                    case ListOptions.SORT_BY_PACKAGE_NAME:
                        return mode * o1.packageName.compareTo(o2.packageName);
                    case ListOptions.SORT_BY_DOMAIN:
                        boolean isSystem1 = (o1.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        boolean isSystem2 = (o2.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        return mode * Boolean.compare(isSystem1, isSystem2);
                    case ListOptions.SORT_BY_LAST_UPDATE:
                        // Sort in decreasing order
                        return -mode * o1.lastUpdateTime.compareTo(o2.lastUpdateTime);
                    case ListOptions.SORT_BY_TOTAL_SIZE:
                        // Sort in decreasing order
                        return -mode * o1.totalSize.compareTo(o2.totalSize);
                    case ListOptions.SORT_BY_INSTALLATION_DATE:
                        // Sort in decreasing order
                        return -mode * Long.compare(o1.firstInstallTime, o2.firstInstallTime);
                    case ListOptions.SORT_BY_TARGET_SDK:
                        // null on top
                        if (o1.sdk == null) return -mode;
                        else if (o2.sdk == null) return +mode;
                        return mode * o1.sdk.compareTo(o2.sdk);
                    case ListOptions.SORT_BY_SHARED_ID:
                        return mode * Integer.compare(o1.uid, o2.uid);
                    case ListOptions.SORT_BY_SHA:
                        // null on top
                        if (o1.sha == null) {
                            return -mode;
                        } else if (o2.sha == null) {
                            return +mode;
                        } else {  // Both aren't null
                            int i = o1.sha.first.compareToIgnoreCase(o2.sha.first);
                            if (i == 0) {
                                return mode * o1.sha.second.compareToIgnoreCase(o2.sha.second);
                            } else return mode * i;
                        }
                    case ListOptions.SORT_BY_BLOCKED_COMPONENTS:
                        if (isRootEnabled) {
                            return -mode * o1.blockedCount.compareTo(o2.blockedCount);
                        }
                        break;
                    case ListOptions.SORT_BY_FROZEN_APP:
                        return -mode * Boolean.compare(o1.isDisabled, o2.isDisabled);
                    case ListOptions.SORT_BY_BACKUP:
                        return -mode * Boolean.compare(o1.backup != null, o2.backup != null);
                    case ListOptions.SORT_BY_LAST_ACTION:
                        return -mode * o1.lastActionTime.compareTo(o2.lastActionTime);
                    case ListOptions.SORT_BY_TRACKERS:
                        return -mode * o1.trackerCount.compareTo(o2.trackerCount);
                }
                return 0;
            });
        }
    }

    @WorkerThread
    private void updateInfoForUid(int uid, String action) {
        Log.d("updateInfoForUid", "Uid: " + uid);
        String[] packages;
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) packages = getPackagesForUid(uid);
        else packages = mPackageManager.getPackagesForUid(uid);
        updateInfoForPackages(packages, action);
    }

    @WorkerThread
    private void updateInfoForPackages(@Nullable String[] packages, @NonNull String action) {
        Log.d("updateInfoForPackages", "packages: " + Arrays.toString(packages));
        if (packages == null || packages.length == 0) return;
        switch (action) {
            case Intent.ACTION_PACKAGE_REMOVED:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                for (String packageName : packages) {
                    try {
                        // This works because these actions are only registered for the current user
                        mPackageManager.getApplicationInfo(packageName, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        removePackageIfNoBackup(packageName);
                    }
                }
                break;
            case PackageChangeReceiver.ACTION_PACKAGE_REMOVED:
                for (String packageName : packages) {
                    ApplicationItem item = getApplicationItemFromApplicationItems(packageName);
                    if (item == null) return;
                    synchronized (applicationItems) {
                        applicationItems.remove(item);
                    }
                }
                sortApplicationList(mSortBy, mSortReverse);
                break;
            case PackageChangeReceiver.ACTION_PACKAGE_ALTERED:
            case PackageChangeReceiver.ACTION_PACKAGE_ADDED:
                for (String packageName : packages) {
                    ApplicationItem item = getNewApplicationItem(packageName, new AppDb().getAllApplications(packageName));
                    if (item != null) insertOrAddApplicationItem(item);
                }
                sortApplicationList(mSortBy, mSortReverse);
                break;
            case Intent.ACTION_PACKAGE_CHANGED:
                for (String packageName : packages) {
                    ApplicationItem item = getNewApplicationItem(packageName);
                    if (item != null) insertApplicationItem(item);
                }
                sortApplicationList(mSortBy, mSortReverse);
                break;
            case Intent.ACTION_PACKAGE_ADDED:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                for (String packageName : packages) {
                    ApplicationItem item = getNewApplicationItem(packageName);
                    if (item != null) insertOrAddApplicationItem(item);
                }
                sortApplicationList(mSortBy, mSortReverse);
                break;
            case BatchOpsService.ACTION_BATCH_OPS_COMPLETED:
                for (String packageName : packages) {
                    ApplicationItem item = getNewApplicationItem(packageName);
                    if (item != null) insertOrAddApplicationItem(item);
                    else removePackageIfNoBackup(packageName);
                }
                sortApplicationList(mSortBy, mSortReverse);
                break;
            default:
                return;
        }
        filterItemsByFlags();
    }

    @WorkerThread
    @GuardedBy("applicationItems")
    private void removePackageIfNoBackup(String packageName) {
        synchronized (applicationItems) {
            ApplicationItem item = getApplicationItemFromApplicationItems(packageName);
            if (item != null) {
                if (item.backup == null) {
                    applicationItems.remove(item);
                    AppDb appDb = new AppDb();
                    for (int userHandle : item.userHandles) {
                        appDb.deleteApplication(item.packageName, userHandle);
                    }
                } else {
                    ApplicationItem changedItem = getNewApplicationItem(packageName);
                    if (changedItem != null) insertOrAddApplicationItem(changedItem);
                }
            }
        }
    }

    @GuardedBy("applicationItems")
    private void insertOrAddApplicationItem(ApplicationItem item) {
        synchronized (applicationItems) {
            if (!insertApplicationItem(item)) {
                applicationItems.add(item);
                if (selectedApplicationItems.contains(item)) {
                    select(item);
                }
            }
        }
    }

    @GuardedBy("applicationItems")
    private boolean insertApplicationItem(@NonNull ApplicationItem item) {
        synchronized (applicationItems) {
            boolean isInserted = false;
            for (int i = 0; i < applicationItems.size(); ++i) {
                if (item.equals(applicationItems.get(i))) {
                    applicationItems.set(i, item);
                    isInserted = true;
                    if (selectedApplicationItems.contains(item)) {
                        select(item);
                    }
                }
            }
            return isInserted;
        }
    }

    @WorkerThread
    @Nullable
    private ApplicationItem getNewApplicationItem(String packageName) {
        ApplicationItem oldItem = null;
        int thisUser = UserHandleHidden.myUserId();
        AppDb appDb = new AppDb();
        for (int userId : Users.getUsersIds()) {
            try {
                @SuppressLint("WrongConstant")
                PackageInfo packageInfo = PackageManagerCompat.getPackageInfo(packageName,
                        PackageManager.GET_META_DATA | flagSigningInfo | PackageManager.GET_ACTIVITIES
                                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                                | PackageManager.GET_SERVICES | flagDisabledComponents, userId);
                App app = App.fromPackageInfo(getApplication(), packageInfo);
                try (ComponentsBlocker cb = ComponentsBlocker.getInstance(app.packageName, app.userId, false)) {
                    app.rulesCount = cb.entryCount();
                }
                ApplicationItem item = new ApplicationItem(packageInfo.applicationInfo);
                if (app.isInstalled && item.equals(oldItem)) {
                    oldItem.userHandles = ArrayUtils.appendInt(oldItem.userHandles, userId);
                    if (userId != thisUser) {
                        // This user has the highest priority
                        continue;
                    }
                    item = oldItem;
                }
                item.versionName = app.versionName;
                item.versionCode = app.versionCode;
                try {
                    if (item.backup == null) {
                        item.backup = BackupUtils.storeAllAndGetLatestBackupMetadata(packageName);
                    }
                } catch (IOException ignore) {
                }
                item.flags = app.flags;
                item.uid = app.uid;
                item.sharedUserId = app.sharedUserId;
                item.label = app.packageLabel;
                item.debuggable = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                item.isUser = (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                item.isDisabled = !app.isEnabled;
                item.hasActivities = app.hasActivities;
                item.hasSplits = app.hasSplits;
                item.firstInstallTime = app.firstInstallTime;
                item.lastUpdateTime = app.lastUpdateTime;
                item.sha = new Pair<>(app.certName, app.certAlgo);
                item.sdk = app.sdk;
                item.userHandles = ArrayUtils.appendInt(item.userHandles, userId);
                item.blockedCount = app.rulesCount;
                item.trackerCount = app.trackerCount;
                item.lastActionTime = app.lastActionTime;
                PackageSizeInfo packageSizeInfo = PackageUtils.getPackageSizeInfo(getApplication(), packageName, userId,
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? packageInfo.applicationInfo.storageUuid : null);
                if (packageSizeInfo != null) {
                    item.totalSize += packageSizeInfo.getTotalSize();
                }
                oldItem = item;
                appDb.insert(app);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return oldItem;
    }

    @WorkerThread
    @Nullable
    private ApplicationItem getNewApplicationItem(String packageName, @NonNull List<App> apps) {
        ApplicationItem oldItem = null;
        int thisUser = UserHandleHidden.myUserId();
        for (App app : apps) {
            try {
                ApplicationItem item = new ApplicationItem();
                item.packageName = app.packageName;
                if (app.isInstalled) {
                    if (oldItem != null) {
                        // Item already exists, add the user handle and continue
                        oldItem.userHandles = ArrayUtils.appendInt(oldItem.userHandles, app.userId);
                        oldItem.isInstalled = true;
                        if (app.userId != thisUser) {
                            // This user has the highest priority
                            continue;
                        }
                        item = oldItem;
                    } else {
                        // Item doesn't exist, add the user handle
                        item.userHandles = ArrayUtils.appendInt(item.userHandles, app.userId);
                        item.isInstalled = true;
                    }
                } else {
                    // App not installed but may be installed in other profiles
                    if (oldItem != null) {
                        // Item exists, use the previous status
                        continue;
                    } else {
                        // Item doesn't exist, don't add user handle
                        item.isInstalled = false;
                    }
                }
                item.packageName = app.packageName;
                item.versionName = app.versionName;
                item.versionCode = app.versionCode;
                try {
                    if (item.backup == null) {
                        item.backup = BackupUtils.storeAllAndGetLatestBackupMetadata(packageName);
                    }
                } catch (IOException ignore) {
                }
                item.flags = app.flags;
                item.uid = app.uid;
                item.sharedUserId = app.sharedUserId;
                item.label = app.packageLabel;
                item.debuggable = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                item.isUser = (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                item.isDisabled = !app.isEnabled;
                item.hasActivities = app.hasActivities;
                item.hasSplits = app.hasSplits;
                item.firstInstallTime = app.firstInstallTime;
                item.lastUpdateTime = app.lastUpdateTime;
                item.sha = new Pair<>(app.certName, app.certAlgo);
                item.sdk = app.sdk;
                item.blockedCount = app.rulesCount;
                item.trackerCount = app.trackerCount;
                item.lastActionTime = app.lastActionTime;
                for (int userId : item.userHandles) {
                    PackageSizeInfo sizeInfo = PackageUtils.getPackageSizeInfo(getApplication(), packageName, userId, null);
                    if (sizeInfo != null) {
                        item.totalSize += sizeInfo.getTotalSize();
                    }
                }
                oldItem = item;
            } catch (Exception ignore) {
            }
        }
        return oldItem;
    }

    @GuardedBy("applicationItems")
    @Nullable
    private ApplicationItem getApplicationItemFromApplicationItems(String packageName) {
        synchronized (applicationItems) {
            for (ApplicationItem item : applicationItems) {
                if (item.packageName.equals(packageName)) return item;
            }
            return null;
        }
    }

    @GuardedBy("applicationItems")
    @NonNull
    private String[] getPackagesForUid(int uid) {
        synchronized (applicationItems) {
            List<String> packages = new LinkedList<>();
            for (ApplicationItem item : applicationItems) {
                if (item.uid == uid) packages.add(item.packageName);
            }
            return packages.toArray(new String[0]);
        }
    }

    @Override
    protected void onCleared() {
        if (mPackageObserver != null) getApplication().unregisterReceiver(mPackageObserver);
        executor.shutdownNow();
        super.onCleared();
    }

    public static class PackageIntentReceiver extends PackageChangeReceiver {
        final MainViewModel mModel;

        public PackageIntentReceiver(@NonNull MainViewModel model) {
            super(model.getApplication());
            mModel = model;
        }

        @Override
        @WorkerThread
        protected void onPackageChanged(Intent intent, @Nullable Integer uid, @Nullable String[] packages) {
            if (uid != null) {
                mModel.updateInfoForUid(uid, intent.getAction());
            } else if (packages != null) {
                mModel.updateInfoForPackages(packages, intent.getAction());
            } else {
                mModel.loadApplicationItems();
            }
        }
    }
}
