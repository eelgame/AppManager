// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.app.Application;
import android.content.pm.UserInfo;
import android.os.UserHandleHidden;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.MetadataManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.MultithreadedExecutor;

public class BackupRestoreDialogViewModel extends AndroidViewModel {
    public static class OperationInfo {
        @Deprecated
        @BackupRestoreDialogFragment.ActionMode
        public int mode;
        @BatchOpsManager.OpType
        public int op;
        @BackupFlags.BackupFlag
        public int flags;
        @Nullable
        public String[] backupNames;
        @Nullable
        public int[] selectedUsers;

        // Others
        public boolean handleMultipleUsers = true;
        @Nullable
        public List<UserInfo> userInfoList;

        public ArrayList<String> packageList;
        public ArrayList<Integer> userIdListMappedToPackageList;
    }

    private int mWorstBackupFlag;

    @NonNull
    private final List<BackupInfo> mBackupInfoList = new ArrayList<>();
    private final Set<CharSequence> mAppsWithoutBackups = new HashSet<>();
    private final Set<CharSequence> mUninstalledApps = new HashSet<>();
    private final MutableLiveData<OperationInfo> mUserSelectionLiveData = new MutableLiveData<>();
    private final MutableLiveData<OperationInfo> mBackupOperationLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> mBackupInfoStateLiveData = new MutableLiveData<>();
    private final ExecutorService mExecutor = MultithreadedExecutor.getNewInstance();

    public BackupRestoreDialogViewModel(@NonNull Application application) {
        super(application);
    }

    @Override
    protected void onCleared() {
        mExecutor.shutdownNow();
        super.onCleared();
    }

    public LiveData<Integer> getBackupInfoStateLiveData() {
        return mBackupInfoStateLiveData;
    }

    public LiveData<OperationInfo> getBackupOperationLiveData() {
        return mBackupOperationLiveData;
    }

    public MutableLiveData<OperationInfo> getUserSelectionLiveData() {
        return mUserSelectionLiveData;
    }

    @NonNull
    public List<BackupInfo> getBackupInfoList() {
        return mBackupInfoList;
    }

    public Set<CharSequence> getAppsWithoutBackups() {
        return mAppsWithoutBackups;
    }

    public Set<CharSequence> getUninstalledApps() {
        return mUninstalledApps;
    }

    @NonNull
    public BackupInfo getBackupInfo() {
        return mBackupInfoList.get(0);
    }

    @BackupFlags.BackupFlag
    public int getWorstBackupFlag() {
        return mWorstBackupFlag;
    }

    @AnyThread
    public void processPackages(@Nullable List<UserPackagePair> userPackagePairs) {
        mExecutor.submit(() -> {
            if (userPackagePairs == null) {
                mBackupInfoStateLiveData.postValue(BackupInfoState.NONE);
                mWorstBackupFlag = 0;
                return;
            }
            Map<String, BackupInfo> backupInfoMap = new HashMap<>();
            // Fetch info
            for (UserPackagePair userPackagePair : userPackagePairs) {
                BackupInfo backupInfo = backupInfoMap.get(userPackagePair.getPackageName());
                if (backupInfo != null) {
                    backupInfo.userIds.add(userPackagePair.getUserHandle());
                    continue;
                }
                backupInfo = new BackupInfo(userPackagePair.getPackageName(), userPackagePair.getUserHandle());
                backupInfoMap.put(userPackagePair.getPackageName(), backupInfo);
                List<App> apps = new AppDb().getAllApplications(userPackagePair.getPackageName());
                List<Backup> backups = AppsDb.getInstance().backupDao().get(userPackagePair.getPackageName());
                // Fetch backup info
                List<MetadataManager.Metadata> metadata = new ArrayList<>();
                for (Backup backup : backups) {
                    try {
                        metadata.add(backup.getMetadata());
                    } catch (IOException e) {
                        // Not found
                        continue;
                    }
                    if (backup.isBaseBackup()) {
                        backupInfo.setHasBaseBackup(true);
                    }
                }
                backupInfo.setBackups(metadata);
                if (apps.isEmpty()) {
                    backupInfo.setInstalled(false);
                } else {
                    for (App app : apps) {
                        backupInfo.setAppLabel(app.packageLabel);
                        // Installation gets higher priority
                        backupInfo.setInstalled(backupInfo.isInstalled() | app.isInstalled);
                    }
                }
            }
            mBackupInfoList.clear();
            mBackupInfoList.addAll(backupInfoMap.values());
            mAppsWithoutBackups.clear();
            mUninstalledApps.clear();
            // Find status
            int status;
            mWorstBackupFlag = 0xffff_ffff;
            if (mBackupInfoList.size() == 1) {
                // Single backup
                BackupInfo backupInfo = mBackupInfoList.get(0);
                for (MetadataManager.Metadata backup : backupInfo.getBackups()) {
                    mWorstBackupFlag &= backup.flags.getFlags();
                }
                if (backupInfo.getBackups().isEmpty()) {
                    mAppsWithoutBackups.add(backupInfo.getAppLabel());
                }
                if (!backupInfo.isInstalled()) {
                    mUninstalledApps.add(backupInfo.getAppLabel());
                }
                if (backupInfo.isInstalled() && !backupInfo.getBackups().isEmpty()) {
                    status = BackupInfoState.BOTH_SINGLE;
                } else if (backupInfo.isInstalled()) {
                    status = BackupInfoState.BACKUP_SINGLE;
                } else if (!backupInfo.getBackups().isEmpty()) {
                    status = BackupInfoState.RESTORE_SINGLE;
                } else status = BackupInfoState.NONE;
            } else {
                // Multiple backup
                boolean hasInstalled = false;
                boolean hasBaseBackup = false;
                for (BackupInfo backupInfo : mBackupInfoList) {
                    if (backupInfo.isInstalled()) {
                        hasInstalled = true;
                    } else {
                        mUninstalledApps.add(backupInfo.getAppLabel());
                    }
                    if (backupInfo.hasBaseBackup()) {
                        hasBaseBackup = true;
                        for (MetadataManager.Metadata backup : backupInfo.getBackups()) {
                            if (backup.isBaseBackup()) {
                                mWorstBackupFlag &= backup.flags.getFlags();
                            }
                        }
                    } else {
                        mAppsWithoutBackups.add(backupInfo.getAppLabel());
                    }
                }
                // Remove irrelevant flags
                int worstBackupFlag = mWorstBackupFlag & ~(BackupFlags.BACKUP_MULTIPLE | BackupFlags.BACKUP_CUSTOM_USERS
                        | BackupFlags.BACKUP_NO_SIGNATURE_CHECK);
                hasBaseBackup = hasBaseBackup && worstBackupFlag > 0;
                if (hasInstalled && hasBaseBackup) {
                    status = BackupInfoState.BOTH_MULTIPLE;
                } else if (hasInstalled) {
                    status = BackupInfoState.BACKUP_MULTIPLE;
                } else if (hasBaseBackup) {
                    status = BackupInfoState.RESTORE_MULTIPLE;
                } else status = BackupInfoState.NONE;
            }
            // Send status
            mBackupInfoStateLiveData.postValue(status);
        });
    }

    @AnyThread
    public void prepareForOperation(@NonNull OperationInfo operationInfo) {
        mExecutor.submit(() -> {
            switch (operationInfo.mode) {
                case BackupRestoreDialogFragment.MODE_BACKUP:
                    if (operationInfo.handleMultipleUsers
                            && (operationInfo.flags & BackupFlags.BACKUP_CUSTOM_USERS) != 0) {
                        handleCustomUsers(operationInfo);
                        return;
                    }
                    break;
                case BackupRestoreDialogFragment.MODE_DELETE:
                    // Nothing to handle, proceed directly to operation
                    break;
                case BackupRestoreDialogFragment.MODE_RESTORE:
                    if (operationInfo.handleMultipleUsers
                            && BuildConfig.DEBUG
                            && (operationInfo.flags & BackupFlags.BACKUP_CUSTOM_USERS) != 0) {
                        handleCustomUsers(operationInfo);
                        return;
                    } else {
                        // Strip custom users flag
                        operationInfo.flags &= ~BackupFlags.BACKUP_CUSTOM_USERS;
                    }
                    break;
            }
            operationInfo.handleMultipleUsers = false;
            generatePackageUserIdLists(operationInfo);
            mBackupOperationLiveData.postValue(operationInfo);
        });
    }

    @WorkerThread
    private void handleCustomUsers(@NonNull OperationInfo operationInfo) {
        operationInfo.handleMultipleUsers = false;
        List<UserInfo> users = Users.getUsers();
        if (users == null || users.size() <= 1) {
            // Strip custom users flag
            operationInfo.flags &= ~BackupFlags.BACKUP_CUSTOM_USERS;
            generatePackageUserIdLists(operationInfo);
            mBackupOperationLiveData.postValue(operationInfo);
            return;
        }
        operationInfo.userInfoList = users;
        mUserSelectionLiveData.postValue(operationInfo);
    }

    @WorkerThread
    private void generatePackageUserIdLists(@NonNull OperationInfo operationInfo) {
        int[] userIds = operationInfo.selectedUsers != null ? operationInfo.selectedUsers : new int[]{UserHandleHidden.myUserId()};
        operationInfo.packageList = new ArrayList<>();
        operationInfo.userIdListMappedToPackageList = new ArrayList<>();
        for (BackupInfo backupInfo : mBackupInfoList) {
            for (int userId : backupInfo.userIds) {
                if (ArrayUtils.contains(userIds, userId)) {
                    operationInfo.packageList.add(backupInfo.packageName);
                    operationInfo.userIdListMappedToPackageList.add(userId);
                }
            }
        }
    }
}
