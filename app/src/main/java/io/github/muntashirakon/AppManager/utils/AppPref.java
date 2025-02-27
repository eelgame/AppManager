// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.UserHandleHidden;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatDelegate;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.github.muntashirakon.AppManager.AppManager;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.signing.SigSchemes;
import io.github.muntashirakon.AppManager.apk.signing.Signer;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.CryptoUtils;
import io.github.muntashirakon.AppManager.compat.PermissionCompat;
import io.github.muntashirakon.AppManager.crypto.auth.AuthManager;
import io.github.muntashirakon.AppManager.details.AppDetailsFragment;
import io.github.muntashirakon.AppManager.logcat.helper.LogcatHelper;
import io.github.muntashirakon.AppManager.main.ListOptions;
import io.github.muntashirakon.AppManager.rules.struct.ComponentRule;
import io.github.muntashirakon.AppManager.runningapps.RunningAppsActivity;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

public class AppPref {
    private static final String PREF_NAME = "preferences";

    private static final int PREF_SKIP = 5;

    /**
     * Preference keys. It's necessary to do things manually as the shared prefs in Android is
     * literary unusable.
     * <br/>
     * Keep these in sync with {@link #getDefaultValue(PrefKey)}.
     */
    public enum PrefKey {
        PREF_APP_OP_SHOW_DEFAULT_BOOL,
        PREF_APP_OP_SORT_ORDER_INT,
        PREF_APP_THEME_INT,
        PREF_APP_THEME_CUSTOM_INT,
        // This is just a placeholder to prevent crash
        PREF_APP_THEME_PURE_BLACK_BOOL,
        // We store this in plain text because if the attackers attack us, they can also attack the other apps
        PREF_AUTHORIZATION_KEY_STR,

        PREF_BACKUP_ANDROID_KEYSTORE_BOOL,
        PREF_BACKUP_COMPRESSION_METHOD_STR,
        PREF_BACKUP_FLAGS_INT,
        PREF_BACKUP_VOLUME_STR,

        PREF_COMPONENTS_SORT_ORDER_INT,
        /**
         * 1 - total cores. 0 = Total cores.
         */
        PREF_CONCURRENCY_THREAD_COUNT_INT,
        PREF_CUSTOM_LOCALE_STR,

        PREF_DISPLAY_CHANGELOG_BOOL,
        PREF_DISPLAY_CHANGELOG_LAST_VERSION_LONG,

        PREF_ENABLE_KILL_FOR_SYSTEM_BOOL,
        PREF_ENABLE_SCREEN_LOCK_BOOL,
        PREF_ENABLED_FEATURES_INT,
        PREF_ENCRYPTION_STR,

        PREF_FREEZE_TYPE_INT,

        PREF_GLOBAL_BLOCKING_ENABLED_BOOL,
        PREF_DEFAULT_BLOCKING_METHOD_STR,

        PREF_INSTALLER_BLOCK_TRACKERS_BOOL,
        PREF_INSTALLER_ALWAYS_ON_BACKGROUND_BOOL,
        PREF_INSTALLER_DISPLAY_CHANGES_BOOL,
        PREF_INSTALLER_DISPLAY_USERS_BOOL,
        PREF_INSTALLER_INSTALL_LOCATION_INT,
        PREF_INSTALLER_INSTALLER_APP_STR,
        PREF_INSTALLER_SIGN_APK_BOOL,

        PREF_LAST_VERSION_CODE_LONG,
        PREF_LAYOUT_ORIENTATION_INT,

        PREF_LOG_VIEWER_BUFFER_INT,
        PREF_LOG_VIEWER_DEFAULT_LOG_LEVEL_INT,
        PREF_LOG_VIEWER_DISPLAY_LIMIT_INT,
        PREF_LOG_VIEWER_EXPAND_BY_DEFAULT_BOOL,
        PREF_LOG_VIEWER_FILTER_PATTERN_STR,
        PREF_LOG_VIEWER_OMIT_SENSITIVE_INFO_BOOL,
        PREF_LOG_VIEWER_SHOW_PID_TID_TIMESTAMP_BOOL,
        PREF_LOG_VIEWER_WRITE_PERIOD_INT,

        PREF_MAIN_WINDOW_FILTER_FLAGS_INT,
        PREF_MAIN_WINDOW_FILTER_PROFILE_STR,
        PREF_MAIN_WINDOW_SORT_ORDER_INT,
        PREF_MAIN_WINDOW_SORT_REVERSE_BOOL,

        PREF_MODE_OF_OPS_STR,
        PREF_OPEN_PGP_PACKAGE_STR,
        PREF_OPEN_PGP_USER_ID_STR,
        PREF_PERMISSIONS_SORT_ORDER_INT,

        PREF_RUNNING_APPS_FILTER_FLAGS_INT,
        PREF_RUNNING_APPS_SORT_ORDER_INT,

        PREF_SAVED_APK_FORMAT_STR,
        PREF_SELECTED_USERS_STR,
        PREF_SIGNATURE_SCHEMES_INT,
        PREF_SHOW_DISCLAIMER_BOOL,

        PREF_VIRUS_TOTAL_API_KEY_STR,
        PREF_VIRUS_TOTAL_PROMPT_BEFORE_UPLOADING_BOOL,
        ;

        public static final String[] keys = new String[values().length];
        @Type
        public static final int[] types = new int[values().length];
        public static final List<PrefKey> prefKeyList = Arrays.asList(values());

        static {
            String keyStr;
            int typeSeparator;
            PrefKey[] keyValues = values();
            for (int i = 0; i < keyValues.length; ++i) {
                keyStr = keyValues[i].name();
                typeSeparator = keyStr.lastIndexOf('_');
                keys[i] = keyStr.substring(PREF_SKIP, typeSeparator).toLowerCase(Locale.ROOT);
                types[i] = inferType(keyStr.substring(typeSeparator + 1));
            }
        }

        public static int indexOf(PrefKey key) {
            return prefKeyList.indexOf(key);
        }

        public static int indexOf(String key) {
            return ArrayUtils.indexOf(keys, key);
        }

        @Type
        private static int inferType(@NonNull String typeName) {
            switch (typeName) {
                case "BOOL":
                    return TYPE_BOOLEAN;
                case "FLOAT":
                    return TYPE_FLOAT;
                case "INT":
                    return TYPE_INTEGER;
                case "LONG":
                    return TYPE_LONG;
                case "STR":
                    return TYPE_STRING;
                default:
                    throw new IllegalArgumentException("Unsupported type.");
            }
        }
    }

    @IntDef(value = {
            TYPE_BOOLEAN,
            TYPE_FLOAT,
            TYPE_INTEGER,
            TYPE_LONG,
            TYPE_STRING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {
    }

    public static final int TYPE_BOOLEAN = 0;
    public static final int TYPE_FLOAT = 1;
    public static final int TYPE_INTEGER = 2;
    public static final int TYPE_LONG = 3;
    public static final int TYPE_STRING = 4;

    @SuppressLint("StaticFieldLeak")
    private static AppPref appPref;

    @NonNull
    public static AppPref getInstance() {
        if (appPref == null) {
            appPref = new AppPref(AppManager.getInstance());
        }
        return appPref;
    }

    @NonNull
    public static AppPref getNewInstance(@NonNull Context context) {
        return new AppPref(context);
    }

    @NonNull
    public static Object get(PrefKey key) {
        int index = PrefKey.indexOf(key);
        AppPref appPref = getInstance();
        switch (PrefKey.types[index]) {
            case TYPE_BOOLEAN:
                return appPref.preferences.getBoolean(PrefKey.keys[index], (boolean) appPref.getDefaultValue(key));
            case TYPE_FLOAT:
                return appPref.preferences.getFloat(PrefKey.keys[index], (float) appPref.getDefaultValue(key));
            case TYPE_INTEGER:
                return appPref.preferences.getInt(PrefKey.keys[index], (int) appPref.getDefaultValue(key));
            case TYPE_LONG:
                return appPref.preferences.getLong(PrefKey.keys[index], (long) appPref.getDefaultValue(key));
            case TYPE_STRING:
                return Objects.requireNonNull(appPref.preferences.getString(PrefKey.keys[index],
                        (String) appPref.getDefaultValue(key)));
        }
        throw new IllegalArgumentException("Unknown key or type.");
    }

    public static boolean getBoolean(PrefKey key) {
        return (boolean) get(key);
    }

    public static int getInt(PrefKey key) {
        return (int) get(key);
    }

    @NonNull
    public static String getString(PrefKey key) {
        return (String) get(key);
    }

    public static boolean isGlobalBlockingEnabled() {
        return getBoolean(PrefKey.PREF_GLOBAL_BLOCKING_ENABLED_BOOL);
    }

    @Nullable
    public static String getVtApiKey() {
        String apiKey = getString(PrefKey.PREF_VIRUS_TOTAL_API_KEY_STR);
        if (TextUtils.isEmpty(apiKey)) {
            return null;
        }
        return apiKey;
    }

    @NonNull
    public static Path getAppManagerDirectory() {
        Uri uri = getSelectedDirectory();
        Path path;
        if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            // Append AppManager
            String newPath = uri.getPath() + File.separator + "AppManager";
            path = Paths.get(newPath);
        } else path = Paths.get(uri);
        if (!path.exists()) path.mkdirs();
        return path;
    }

    public static boolean backupDirectoryExists(Context context) {
        Uri uri = getSelectedDirectory();
        Path path;
        if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            // Append AppManager only if storage permissions are granted
            String newPath = uri.getPath();
            if (PermissionUtils.hasStoragePermission(context) || Ops.isPrivileged()) {
                newPath += File.separator + "AppManager";
            }
            path = Paths.get(newPath);
        } else path = Paths.get(uri);
        return path.exists();
    }

    public static Uri getSelectedDirectory() {
        String uriOrBareFile = getString(PrefKey.PREF_BACKUP_VOLUME_STR);
        if (uriOrBareFile.startsWith("/")) {
            // A good URI starts with file:// or content://, if not, migrate
            Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_FILE).path(uriOrBareFile).build();
            set(PrefKey.PREF_BACKUP_VOLUME_STR, uri.toString());
            return uri;
        }
        return Uri.parse(uriOrBareFile);
    }

    @Nullable
    public static int[] getSelectedUsers() {
        if (!Ops.isPrivileged()) return null;
        String usersStr = getString(PrefKey.PREF_SELECTED_USERS_STR);
        if ("".equals(usersStr)) return null;
        String[] usersSplitStr = usersStr.split(",");
        int[] users = new int[usersSplitStr.length];
        for (int i = 0; i < users.length; ++i) {
            users[i] = Integer.decode(usersSplitStr[i]);
        }
        return users;
    }

    public static void setSelectedUsers(@Nullable int[] users) {
        if (users == null || !Ops.isPrivileged()) {
            set(PrefKey.PREF_SELECTED_USERS_STR, "");
            return;
        }
        String[] userString = new String[users.length];
        for (int i = 0; i < users.length; ++i) {
            userString[i] = String.valueOf(users[i]);
        }
        set(PrefKey.PREF_SELECTED_USERS_STR, TextUtils.join(",", userString));
    }

    @NonNull
    public static String getLanguage(Context context) {
        AppPref appPref = getNewInstance(context);
        PrefKey key = PrefKey.PREF_CUSTOM_LOCALE_STR;
        return Objects.requireNonNull(appPref.preferences.getString(PrefKey.keys[PrefKey.indexOf(key)],
                (String) appPref.getDefaultValue(key)));
    }

    @ComponentRule.ComponentStatus
    public static String getDefaultComponentStatus() {
        String selectedStatus = getString(PrefKey.PREF_DEFAULT_BLOCKING_METHOD_STR);
        if (Ops.isAdb()) {
            if (selectedStatus.equals(ComponentRule.COMPONENT_TO_BE_BLOCKED_IFW_DISABLE)
                    || selectedStatus.equals(ComponentRule.COMPONENT_TO_BE_BLOCKED_IFW)) {
                // Lower the status
                return ComponentRule.COMPONENT_TO_BE_DISABLED;
            }
        }
        return selectedStatus;
    }

    @FreezeUtils.FreezeType
    public static int getDefaultFreezingMethod() {
        int freezeType = getInt(PrefKey.PREF_FREEZE_TYPE_INT);
        if (freezeType == FreezeUtils.FREEZE_HIDE && PermissionCompat.checkSelfPermission(PermissionUtils.PERMISSION_MANAGE_USERS, UserHandleHidden.myUserId()) != PackageManager.PERMISSION_GRANTED) {
            return FreezeUtils.FREEZE_DISABLE;
        }
        if (freezeType == FreezeUtils.FREEZE_SUSPEND && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return FreezeUtils.FREEZE_DISABLE;
        }
        return freezeType;
    }

    public static int getLayoutOrientation() {
        return getInt(PrefKey.PREF_LAYOUT_ORIENTATION_INT);
    }

    @StyleRes
    public static int getAppTheme() {
        switch (getInt(PrefKey.PREF_APP_THEME_CUSTOM_INT)) {
            case 1: // Full black theme
                return R.style.AppTheme_Black;
            default: // Normal theme
                return R.style.AppTheme;
        }
    }

    @StyleRes
    public static int getTransparentAppTheme() {
        switch (getInt(PrefKey.PREF_APP_THEME_CUSTOM_INT)) {
            case 1: // Full black theme
                return R.style.AppTheme_TransparentBackground_Black;
            default: // Normal theme
                return R.style.AppTheme_TransparentBackground;
        }
    }

    public static boolean isPureBlackTheme() {
        return getInt(PrefKey.PREF_APP_THEME_CUSTOM_INT) == 1;
    }

    public static void setPureBlackTheme(boolean enabled) {
        set(PrefKey.PREF_APP_THEME_CUSTOM_INT, enabled ? 1 : 0);
    }

    public static boolean canSignApk() {
        if (!getBoolean(PrefKey.PREF_INSTALLER_SIGN_APK_BOOL)) {
            // Signing not enabled
            return false;
        }
        return Signer.canSign();
    }

    public static void set(PrefKey key, Object value) {
        getInstance().setPref(key, value);
    }

    public static void setDefault(PrefKey key) {
        AppPref appPref = getInstance();
        appPref.setPref(key, appPref.getDefaultValue(key));
    }

    @NonNull
    private final SharedPreferences preferences;
    @NonNull
    private final SharedPreferences.Editor editor;

    private final Context context;

    @SuppressLint("CommitPrefEdits")
    private AppPref(@NonNull Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = preferences.edit();
        init();
    }

    public void setPref(PrefKey key, Object value) {
        int index = PrefKey.indexOf(key);
        if (value instanceof Boolean) editor.putBoolean(PrefKey.keys[index], (Boolean) value);
        else if (value instanceof Float) editor.putFloat(PrefKey.keys[index], (Float) value);
        else if (value instanceof Integer) editor.putInt(PrefKey.keys[index], (Integer) value);
        else if (value instanceof Long) editor.putLong(PrefKey.keys[index], (Long) value);
        else if (value instanceof String) editor.putString(PrefKey.keys[index], (String) value);
        editor.apply();
        editor.commit();
    }

    public void setPref(String key, @Nullable Object value) {
        int index = PrefKey.indexOf(key);
        if (index == -1) throw new IllegalArgumentException("Invalid key: " + key);
        // Set default value if the requested value is null
        if (value == null) value = getDefaultValue(PrefKey.prefKeyList.get(index));
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        editor.apply();
        editor.commit();
    }

    @NonNull
    public Object get(String key) {
        int index = PrefKey.indexOf(key);
        if (index == -1) throw new IllegalArgumentException("Invalid key: " + key);
        Object defaultValue = getDefaultValue(PrefKey.prefKeyList.get(index));
        switch (PrefKey.types[index]) {
            case TYPE_BOOLEAN:
                return preferences.getBoolean(key, (boolean) defaultValue);
            case TYPE_FLOAT:
                return preferences.getFloat(key, (float) defaultValue);
            case TYPE_INTEGER:
                return preferences.getInt(key, (int) defaultValue);
            case TYPE_LONG:
                return preferences.getLong(key, (long) defaultValue);
            case TYPE_STRING:
                return Objects.requireNonNull(preferences.getString(key, (String) defaultValue));
        }
        throw new IllegalArgumentException("Unknown key or type.");
    }

    private void init() {
        for (int i = 0; i < PrefKey.keys.length; ++i) {
            if (!preferences.contains(PrefKey.keys[i])) {
                switch (PrefKey.types[i]) {
                    case TYPE_BOOLEAN:
                        editor.putBoolean(PrefKey.keys[i], (boolean) getDefaultValue(PrefKey.prefKeyList.get(i)));
                        break;
                    case TYPE_FLOAT:
                        editor.putFloat(PrefKey.keys[i], (float) getDefaultValue(PrefKey.prefKeyList.get(i)));
                        break;
                    case TYPE_INTEGER:
                        editor.putInt(PrefKey.keys[i], (int) getDefaultValue(PrefKey.prefKeyList.get(i)));
                        break;
                    case TYPE_LONG:
                        editor.putLong(PrefKey.keys[i], (long) getDefaultValue(PrefKey.prefKeyList.get(i)));
                        break;
                    case TYPE_STRING:
                        editor.putString(PrefKey.keys[i], (String) getDefaultValue(PrefKey.prefKeyList.get(i)));
                }
            }
        }
        editor.apply();
    }

    @NonNull
    public Object getDefaultValue(@NonNull PrefKey key) {
        switch (key) {
            case PREF_BACKUP_FLAGS_INT:
                return BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_RULES
                        | BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_EXTRAS;
            case PREF_BACKUP_COMPRESSION_METHOD_STR:
                return TarUtils.TAR_GZIP;
            case PREF_ENABLE_KILL_FOR_SYSTEM_BOOL:
            case PREF_GLOBAL_BLOCKING_ENABLED_BOOL:
            case PREF_INSTALLER_ALWAYS_ON_BACKGROUND_BOOL:
            case PREF_INSTALLER_BLOCK_TRACKERS_BOOL:
            case PREF_INSTALLER_DISPLAY_USERS_BOOL:
            case PREF_INSTALLER_SIGN_APK_BOOL:
            case PREF_BACKUP_ANDROID_KEYSTORE_BOOL:
            case PREF_ENABLE_SCREEN_LOCK_BOOL:
            case PREF_MAIN_WINDOW_SORT_REVERSE_BOOL:
            case PREF_LOG_VIEWER_EXPAND_BY_DEFAULT_BOOL:
            case PREF_LOG_VIEWER_OMIT_SENSITIVE_INFO_BOOL:
            case PREF_APP_THEME_PURE_BLACK_BOOL:
            case PREF_DISPLAY_CHANGELOG_BOOL:
                return false;
            case PREF_APP_OP_SHOW_DEFAULT_BOOL:
            case PREF_SHOW_DISCLAIMER_BOOL:
            case PREF_LOG_VIEWER_SHOW_PID_TID_TIMESTAMP_BOOL:
            case PREF_INSTALLER_DISPLAY_CHANGES_BOOL:
            case PREF_VIRUS_TOTAL_PROMPT_BEFORE_UPLOADING_BOOL:
                return true;
            case PREF_CONCURRENCY_THREAD_COUNT_INT:
            case PREF_APP_THEME_CUSTOM_INT:
                return 0;
            case PREF_LAST_VERSION_CODE_LONG:
            case PREF_DISPLAY_CHANGELOG_LAST_VERSION_LONG:
                return 0L;
            case PREF_ENABLED_FEATURES_INT:
                return 0xffff_ffff;  /* All features enabled */
            case PREF_APP_THEME_INT:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            case PREF_MAIN_WINDOW_FILTER_FLAGS_INT:
                return ListOptions.FILTER_NO_FILTER;
            case PREF_MAIN_WINDOW_SORT_ORDER_INT:
                return ListOptions.SORT_BY_APP_LABEL;
            case PREF_CUSTOM_LOCALE_STR:
                return LangUtils.LANG_AUTO;
            case PREF_APP_OP_SORT_ORDER_INT:
            case PREF_COMPONENTS_SORT_ORDER_INT:
            case PREF_PERMISSIONS_SORT_ORDER_INT:
                return AppDetailsFragment.SORT_BY_NAME;
            case PREF_RUNNING_APPS_SORT_ORDER_INT:
                return RunningAppsActivity.SORT_BY_PID;
            case PREF_RUNNING_APPS_FILTER_FLAGS_INT:
                return RunningAppsActivity.FILTER_NONE;
            case PREF_ENCRYPTION_STR:
                return CryptoUtils.MODE_NO_ENCRYPTION;
            case PREF_OPEN_PGP_PACKAGE_STR:
            case PREF_OPEN_PGP_USER_ID_STR:
            case PREF_MAIN_WINDOW_FILTER_PROFILE_STR:
            case PREF_SELECTED_USERS_STR:
            case PREF_VIRUS_TOTAL_API_KEY_STR:
                return "";
            case PREF_MODE_OF_OPS_STR:
                return Ops.MODE_AUTO;
            case PREF_INSTALLER_INSTALL_LOCATION_INT:
                return PackageInfo.INSTALL_LOCATION_AUTO;
            case PREF_INSTALLER_INSTALLER_APP_STR:
                return AppManager.getContext().getPackageName();
            case PREF_SIGNATURE_SCHEMES_INT:
                return SigSchemes.SIG_SCHEME_V1 | SigSchemes.SIG_SCHEME_V2;
            case PREF_BACKUP_VOLUME_STR:
                return Uri.fromFile(Environment.getExternalStorageDirectory()).toString();
            case PREF_LOG_VIEWER_FILTER_PATTERN_STR:
                return context.getString(R.string.pref_filter_pattern_default);
            case PREF_LOG_VIEWER_DISPLAY_LIMIT_INT:
                return 10_000;
            case PREF_LOG_VIEWER_WRITE_PERIOD_INT:
                return 200;
            case PREF_LOG_VIEWER_DEFAULT_LOG_LEVEL_INT:
                return Log.VERBOSE;
            case PREF_LOG_VIEWER_BUFFER_INT:
                return LogcatHelper.LOG_ID_DEFAULT;
            case PREF_LAYOUT_ORIENTATION_INT:
                return View.LAYOUT_DIRECTION_LTR;
            case PREF_DEFAULT_BLOCKING_METHOD_STR:
                // This is default for root
                return ComponentRule.COMPONENT_TO_BE_BLOCKED_IFW_DISABLE;
            case PREF_SAVED_APK_FORMAT_STR:
                return "%label%_%version%";
            case PREF_AUTHORIZATION_KEY_STR:
                return AuthManager.generateKey();
            case PREF_FREEZE_TYPE_INT:
                return FreezeUtils.FREEZE_DISABLE;
        }
        throw new IllegalArgumentException("Pref key not found.");
    }
}
