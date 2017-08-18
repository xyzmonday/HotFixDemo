package com.richfit.hotfixcore;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.richfit.hotfixcore.utils.AssetUtils;
import com.richfit.hotfixcore.utils.DexUtils;

import java.io.File;
import java.io.IOException;

/**
 * Created by monday on 15/10/31.
 */
public class HotFix {

    private static final String TAG = "yff";
    //预加载的dex
    private static final String HACK_DEX = "hack_dex.jar";
    //将assets目录下的文件以及服务器下载的补丁文件拷贝到私有目录的文件目录
    private static final String DEX_DIR = "hotfix";
    //.jar->dex解压的目录
    private static final String DEX_OPT_DIR = "hotfixopt";


    public static void init(Context context) {
        File dexDir = new File(context.getFilesDir(), DEX_DIR);
        dexDir.mkdir();

        String dexPath = null;
        try {
            dexPath = AssetUtils.copyAsset(context, HACK_DEX, dexDir);
        } catch (IOException e) {
            Log.e(TAG, "copy " + HACK_DEX + " failed");
            e.printStackTrace();
        }

        loadPatch(context, dexPath);
    }

    private static void loadPatch(Context context, String dexPath) {

        if (context == null) {
            Log.e(TAG, "context is null");
            return;
        }
        if (!new File(dexPath).exists()) {
            Log.e(TAG, dexPath + " is null");
            return;
        }
        File dexOptDir = new File(context.getFilesDir(), DEX_OPT_DIR);
        if (!dexOptDir.exists())
            dexOptDir.mkdir();
        try {
            DexUtils.injectDexAtFirst(dexPath, dexOptDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "inject " + dexPath + " failed");
            e.printStackTrace();
        }
    }

    public static void hotFix(Context context, String patchName) {
        //1.第一步将patch文件拷贝到私有目录
        try {
            File dexDir = new File(context.getFilesDir(), DEX_DIR);
            if (!dexDir.exists()) {
                dexDir.mkdir();
            }
            String patchDir = Environment.getExternalStorageDirectory().getAbsolutePath();
            File in = new File(patchDir, patchName);
            if (!in.exists())
                return;
            File out = new File(dexDir, patchName);
            String dexPath = null;

            dexPath = AssetUtils.copyFile(in, out);
            //2.加载补丁文件
            loadPatch(context, dexPath);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("yff", "加载补丁异常 = " + e.getMessage());
        }
    }
}
