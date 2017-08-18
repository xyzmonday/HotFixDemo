package com.richfit.hotfixdemo;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.richfit.hotfixcore.HotFix;

import java.lang.reflect.Method;

/**
 * Created by monday on 2017/7/30.
 */

public class HotFixApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        HotFix.init(this);
        HotFix.hotFix(this, "patch.jar");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("yff", "HotFixApplication oncreat");
        try {
            Class<?> clazz = Class.forName("com.richfit.hack.AntilazyLoad");
            Log.e("yff", "className = " + clazz.getName());

//            Class<?> clz = Class.forName("com.richfit.hotfixdemo.Test");
//            Object o = clz.newInstance();
//            Method method = clz.getDeclaredMethod("say", new Class[]{});
//            method.invoke(o, new Object[]{});

        } catch (Exception e) {
            e.printStackTrace();
            Log.e("yff", e.getCause().getMessage());
        }
    }
}
