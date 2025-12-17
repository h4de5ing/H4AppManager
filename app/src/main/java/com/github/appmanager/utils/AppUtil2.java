package com.github.appmanager.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import com.github.appmanager.model.AppModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by gh0st on 2017/2/18.
 */

public class AppUtil2 {
    public static List<AppModel> getInstallApp(Context context) {
        List<AppModel> appDatas = new ArrayList<AppModel>();
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> infoList = pm.getInstalledPackages(0);
        for (PackageInfo info : infoList) {
            AppModel appModel = new AppModel();
            String appName = AppUtils.getAppName(context, info.packageName);
            Drawable appIcon = AppUtils.getAppIcon(context, info.packageName);
            String appDate = DateUtils.formatDate(AppUtils.getAppFirstInstallTime(context, info.packageName));
            String appSize = FileUtils.formatFileSize(context, AppUtils.getAppSize(context, info.packageName));
            appModel.setAppName(appName);
            appModel.setAppIcon(appIcon);
            appModel.setAppDate(appDate);
            appModel.setAppSize(appSize);
            appModel.setAppPack(info.packageName);
            appModel.setAppApk(getAppApk(context, info.packageName));
            int flags = info.applicationInfo.flags;
            if ((flags & ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM) {
                appModel.setSystem(true);
            } else {
                appModel.setSystem(false);
            }
            if (!TextUtils.isEmpty(appName) && !TextUtils.isEmpty(appDate) && !TextUtils.isEmpty(appSize)) {
                appDatas.add(appModel);
            }
        }
        return appDatas;
    }

    private static String getAppApk(Context context, String packageName) {
        String sourceDir = null;
        try {
            ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
            sourceDir = applicationInfo.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return sourceDir;
    }

    public static String getAppDataDir(Context context, String packageName) {
        String dataDir = null;
        try {
            ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
            dataDir = applicationInfo.dataDir;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return dataDir;
    }
}
