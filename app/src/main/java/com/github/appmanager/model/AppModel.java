package com.github.appmanager.model;

import android.graphics.drawable.Drawable;

/**
 * Created by gh0st on 2017/2/17.
 */

public class AppModel {
    private Drawable appIcon;
    private String appName;
    private String appSize;
    private String appDate;
    private String appApk;
    private String appPack;
    private boolean isSystem;

    public boolean isSystem() {
        return isSystem;
    }

    public void setSystem(boolean system) {
        isSystem = system;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }


    public String getAppPack() {
        return appPack;
    }

    public void setAppPack(String appPack) {
        this.appPack = appPack;
    }


    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getAppSize() {
        return appSize;
    }

    public void setAppSize(String appSize) {
        this.appSize = appSize;
    }

    public String getAppDate() {
        return appDate;
    }

    public void setAppDate(String appDate) {
        this.appDate = appDate;
    }

    public String getAppApk() {
        return appApk;
    }

    public void setAppApk(String appApk) {
        this.appApk = appApk;
    }

    @Override
    public String toString() {
        return "appName='" + appName + '\'' +
                ", appSize='" + appSize + '\'' +
                ", appDate='" + appDate + '\'' +
                ", appApk='" + appApk + '\'' +
                ", appPack='" + appPack + '\'' +
                ", isSystem=" + isSystem +
                '}';
    }
}
