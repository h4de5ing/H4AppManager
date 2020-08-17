package com.github.appmanager.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.code19.library.AppUtils;
import com.code19.library.DateUtils;
import com.code19.library.FileUtils;
import com.code19.library.SpannableStringUtils;
import com.github.appmanager.R;
import com.github.appmanager.model.AppModel;

/**
 * Created by gh0st on 2017/2/17.
 */

public class ViewUtils {
    /**
     * 应用信息
     *
     * @param activity
     * @param appModel
     */
    public static void appInfoDialog(final Activity activity, final AppModel appModel) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setCancelable(false);
        dialog.setIcon(appModel.getAppIcon());
        dialog.setTitle(appModel.getAppName());
        String packname = appModel.getAppPack();
        dialog.setMessage(SpannableStringUtils.getBuilder(activity, activity.getString(R.string.app_info_packname, packname))
                .append(activity.getString(R.string.app_info,
                        AppUtils.getAppApk(activity, packname),
                        AppUtil2.getAppDataDir(activity, packname),
                        AppUtils.getAppVersionName(activity, packname),
                        AppUtils.getAppVersionCode(activity, packname),
                        FileUtils.formatFileSize(activity, AppUtils.getAppSize(activity, packname)),
                        DateUtils.formatDataTime(AppUtils.getAppFirstInstallTime(activity, packname)),
                        DateUtils.formatDataTime(AppUtils.getAppLastUpdateTime(activity, packname)),
                        AppUtils.getAppTargetSdkVersion(activity, packname),
                        AppUtils.getAppUid(activity, packname),
                        AppUtils.getAppSign(activity, packname)
                )).create());
        dialog.setNegativeButton(activity.getString(R.string.app_info_share_apk), (dialog1, which) -> {
            try {
                String filepath = FileUtils2.getApkFilePath(activity) + appModel.getAppPack() + ".apk";
                FileUtils2.copy(appModel.getAppApk(), filepath, false);
                FileUtils.shareFile(activity, activity.getString(R.string.app_info_share_apk_to_friend, appModel.getAppName()), filepath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        dialog.setPositiveButton(android.R.string.cancel, (dialog12, which) -> {

        });
        dialog.setNeutralButton(activity.getString(R.string.app_info_load_apk), (dialog13, which) -> {
            try {
                FileUtils2.copy(appModel.getAppApk(), FileUtils2.getApkFilePath(activity) + appModel.getAppPack() + ".apk", false);
                Toast.makeText(activity, activity.getString(R.string.app_info_load_apk_success), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(activity, activity.getString(R.string.app_info_load_apk_fail), Toast.LENGTH_SHORT).show();
            }
        });
        if (!activity.isFinishing()) {
            dialog.show();
        }
    }

    public static void openAPk(Activity activity) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setCancelable(true);
        dialog.setIcon(AppUtils.getAppIcon(activity, activity.getPackageName()));
        dialog.setTitle(activity.getString(R.string.action_apk_path));
        dialog.setMessage("APK Dir:" + FileUtils2.getApkFilePath(activity));
        dialog.setNegativeButton(activity.getString(R.string.open_tips), null);
        if (!activity.isFinishing()) {
            dialog.show();
        }
    }

    /**
     * 开源许可
     */
    public static void openDialog(Activity activity) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setCancelable(true);
        dialog.setIcon(AppUtils.getAppIcon(activity, activity.getPackageName()));
        dialog.setTitle(activity.getString(R.string.action_open));
        dialog.setMessage(SpannableStringUtils.getBuilder(activity, activity.getString(R.string.license))
                .append(activity.getString(R.string.license2))
                .create());
        dialog.setNegativeButton(activity.getString(R.string.open_tips), null);
        if (!activity.isFinishing()) {
            dialog.show();
        }
    }

    /**
     * 关于
     */
    public static void aboutDialog(final Activity activity) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setCancelable(true);
        dialog.setIcon(AppUtils.getAppIcon(activity, activity.getPackageName()));
        dialog.setTitle(activity.getString(R.string.action_about) + " " + AppUtils.getAppVersionName(activity, activity.getPackageName()));
        dialog.setMessage(SpannableStringUtils.getBuilder(activity, activity.getString(R.string.about))
                .create());
        dialog.setNegativeButton(activity.getString(R.string.about_tips0), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                FileUtils.openURL(activity, activity.getString(R.string.about_website));
            }
        });
        dialog.setPositiveButton(activity.getString(R.string.about_tips1), null);
        if (!activity.isFinishing()) {
            dialog.show();
        }
    }

}
