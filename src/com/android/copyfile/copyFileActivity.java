package com.android.copyfile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.IPackageInstallObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.Log;

import java.io.*;
import java.util.*;

public class copyFileActivity extends Activity
{
    private static final String TAG = "CopyFile";

    private TextView       mTxtStatus;
    private TextView       mTxtMain;
    private TextView       mTxtSub;
    private ProgressBar    mProgressMain;
    private ProgressBar    mProgressSub;
    private Thread         mThread;
    private boolean        mExitCopy;
    private PowerManager   mPowerManager;
    private PackageManager mPackageManager;
    private PowerManager.WakeLock mWakeLock;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.copyfile);

        mTxtStatus    = (TextView)findViewById(R.id.txt_status );
        mTxtMain      = (TextView)findViewById(R.id.txt_main   );
        mTxtSub       = (TextView)findViewById(R.id.txt_sub    );
        mProgressMain = (ProgressBar)findViewById(R.id.bar_main);
        mProgressSub  = (ProgressBar)findViewById(R.id.bar_sub );

        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock     = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);

        mPackageManager  = getPackageManager();
        mInstallObserver = new PackageInstallObserver(mHandler);

        mExitCopy = false;
        mThread   = new Thread() {
            @Override
            public void run() {
                mWakeLock.acquire();
                doCopyFile();
                mThread = null;
                mWakeLock.release();
            }
        };
        mThread.start();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "++onDestroy");
        mExitCopy = true;
        synchronized (mApkInstallEvent) {
            mApkInstallEvent.notifyAll();
        }
        try { mThread.join(); } catch (Exception e) { e.printStackTrace(); }
        Log.d(TAG, "--onDestroy");
        super.onDestroy();
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (mThread == null) {
            super.onBackPressed();
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.notice));
            builder.setIcon(R.drawable.ic_launcher);
            builder.setMessage(getString(R.string.wait_copy_done));
            builder.setPositiveButton(
                getString(R.string.confirm),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {}
                }
            );
            builder.create();
            builder.show();
        }
    }

    private static final String COPYFILE_CONFIG_PATH = "/mnt/extsd/copyfile/config.ini";
//  private static final String COPYFILE_CONFIG_PATH = "/sdcard/config.ini";
    private ArrayList<CopyTask> parseCopyFileConfig(String path) {
        ArrayList<CopyTask> tasklist = new ArrayList<CopyTask>();
        FileInputStream is     = null;
        BufferedReader  reader = null;
        String          buffer = null;
        CopyTask        item   = null;
        try {
            File file = new File(path);
            is     = new FileInputStream(file);
            reader = new BufferedReader(new InputStreamReader(is));
            while ((buffer = reader.readLine()) != null) {
                buffer = buffer.trim();
                if (buffer.equals("[file]")) {
                    if (item != null) tasklist.add(item);
                    item = new CopyTask();
                    item.type = CopyTask.COPY_TASK_FILE;
                } else if (buffer.equals("[dir]")) {
                    if (item != null) tasklist.add(item);
                    item = new CopyTask();
                    item.type = CopyTask.COPY_TASK_DIR;
                } else if (buffer.equals("[unzip]")) {
                    if (item != null) tasklist.add(item);
                    item = new CopyTask();
                    item.type = CopyTask.COPY_TASK_UNZIP;
                } else if (buffer.equals("[apk]")) {
                    if (item != null) tasklist.add(item);
                    item = new CopyTask();
                    item.type = CopyTask.COPY_TASK_APK;
                } else if (item != null) {
                    String[] aa = buffer.split("=");
                    if (aa != null && aa.length >= 2) {
                        if (aa[0].equals("src")) {
                            item.src = aa[1];
                        } else if (aa[0].equals("dst")) {
                            item.dst = aa[1];
                        } else if (aa[0].equals("checksum")) {
                            item.checksum = Long.parseLong(aa[1]);
                        }
                    }
                }
            }
            if (item != null) tasklist.add(item);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) reader.close();
                if (is     != null) is    .close();
            } catch (Exception e) {}
        }
        return tasklist;
    }

    private long getDirSize(String path) {
        long total = 0;
        File file = new File(path);
        if (file.exists()) {
            File[] files = file.listFiles();
            if (files.length != 0) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        total += getDirSize(f.getAbsolutePath());
                    } else {
                        total += f.length();
                    }
                }
            }
        }
        return total;
    }

    private long getFileSize(String path) {
        File file = new File(path);
        if (file.exists() && file.isFile()) {
            return file.length();
        }
        return -1;
    }

    private void mediaScanDir(String dir) {
        Intent intent = new Intent("android.intent.action.MEDIA_SCANNER_SCAN_DIR");
        intent.setData(Uri.fromFile(new File(dir)));
        sendBroadcast(intent);
    }

    private long mLastReportTime = 0;
    private long mCurBytesCopyed = 0;
    private boolean copyFile(String src, String dst) {
//      Log.d(TAG, "copyfile: " + src + " -> " + dst);
        File fsrc = new File(src);
        File fdst = new File(dst);
        InputStream  is = null;
        OutputStream os = null;
        boolean     ret = false;
        byte[] buf = new byte[1024];
        try {
            is = new FileInputStream (fsrc);
            os = new FileOutputStream(fdst);
            int bytesRead = 0;
            int bytesCopy = 0;
            while (!mExitCopy && (bytesRead = is.read(buf)) > 0) {
                os.write(buf, 0, bytesRead);
                bytesCopy += bytesRead;
                if (SystemClock.uptimeMillis() - mLastReportTime > 100) {
                    mLastReportTime = SystemClock.uptimeMillis();
                    sendMessage(CopyTask.MSG_BYTES_COPY, bytesCopy, 0, dst);
                    bytesCopy = 0;
                }
            }
            sendMessage(CopyTask.MSG_BYTES_COPY, bytesCopy, 0, dst);
            ret = true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) is.close();
                if (os != null) os.close();
            } catch (Exception e) {}
        }
        if (!ret) sendMessage(CopyTask.MSG_FILE_FAILED, 0, 0, dst);
        return ret;
    }

    private boolean copyDir(String src, String dst) {
//      Log.d(TAG, "copydir: " + src + " -> " + dst);
        File fsrc   = new File(src);
        File fdst   = new File(dst);
        boolean ret = false;
        if (fsrc.exists()) {
            fdst.mkdirs();
            File[] srcfiles = fsrc.listFiles();
            if (srcfiles.length != 0) {
                for (File f : srcfiles) {
                    if (!dst.endsWith(File.separator)) dst += File.separator;
                    if (f.isDirectory()) {
                        ret = copyDir (f.getAbsolutePath(), dst + f.getName());
                    } else {
                        ret = copyFile(f.getAbsolutePath(), dst + f.getName());
                    }
                    if (!ret) return false;
                }
            }
        }
        return true;
    }

    private boolean unzipFile(String src, String dst) {
        return false;
    }

    private PackageInstallObserver mInstallObserver = null;
    private ArrayList<String>      mApkFileList     = new ArrayList<String>();
    private final Object           mApkInstallEvent = new Object();
    private boolean                mApkInstallResult= false;
    private void searchApkFiles(String src) {
        File fsrc   = new File(src);
        if (fsrc.exists()) {
            File[] srcfiles = fsrc.listFiles();
            if (srcfiles.length != 0) {
                for (File f : srcfiles) {
                    if (f.isDirectory()) {
                        searchApkFiles(f.getAbsolutePath());
                    } else {
                        mApkFileList.add(f.getAbsolutePath());
                    }
                }
            }
        }
    }

    private boolean installApk(String path) {
        Uri           packageUri    = Uri.fromFile(new File(path));
        File          packageFile   = new File(path);
        String        packageName   = "";

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
            PackageParser packageParser = new PackageParser();
            PackageParser.Package pack  = null;
            try {
                pack = packageParser.parsePackage(packageFile, 0);
            } catch (Exception e) { e.printStackTrace(); }
            if (pack == null) {
                sendMessage(CopyTask.MSG_APK_INSTALL_FAILED, 0, 0, null);
                return false;
            }
            packageName = pack.applicationInfo.packageName;
        } else {
            PackageInfo archiveinfo = mPackageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES);
            if (archiveinfo == null) {
                sendMessage(CopyTask.MSG_APK_INSTALL_FAILED, 0, 0, null);
                return false;
            }
            packageName = archiveinfo.applicationInfo.packageName;
        }

        sendMessage(CopyTask.MSG_APK_INSTALL_RUNNING, 0, 0, packageName);
        if (!packageName.equals("com.android.copyfile")) { // to avoid kill self
            mPackageManager.installPackage(packageUri, mInstallObserver, PackageManager.INSTALL_REPLACE_EXISTING, packageName);
            synchronized (mApkInstallEvent) {
                mApkInstallResult = false;
                try { mApkInstallEvent.wait(); } catch (Exception e) { e.printStackTrace(); }
            }
        }
        sendMessage(mApkInstallResult ? CopyTask.MSG_APK_INSTALL_SUCCESSED : CopyTask.MSG_APK_INSTALL_FAILED, 0, 0, packageName);
        return mApkInstallResult;
    }

    private boolean installAllApks(String src) {
        boolean ret = true;
        mApkFileList.clear();
        searchApkFiles(src);
        if (mApkFileList.size() < 0) return true;

        sendMessage(CopyTask.MSG_APK_INSTALL_TOTAL, mApkFileList.size(), 0, null);
        for (String path : mApkFileList) {
            ret = installApk(path);
            if (!ret || mExitCopy) break;
        }
        if (ret) sendMessage(CopyTask.MSG_APK_INSTALL_DONE, 0, 0, null);
        return ret;
    }

    private void sendMessage(int what, int arg1, int arg2, Object obj) {
        Message msg = new Message();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj  = obj;
        mHandler.sendMessage(msg);
    }

    private boolean doCopyFile() {
        boolean ret = false;
        sendMessage(CopyTask.MSG_COPY_START, 0, 0, null);
        ArrayList<CopyTask> list = parseCopyFileConfig(COPYFILE_CONFIG_PATH);
        sendMessage(CopyTask.MSG_TASK_TOTAL, list.size(), 0, null);
        for (CopyTask task : list) {
            sendMessage(CopyTask.MSG_TASK_START, 0, 0, task);
            switch (task.type) {
            case CopyTask.COPY_TASK_FILE: {
                    long filesize = getFileSize(task.src);
                    if (task.checksum != 0 && task.checksum != filesize) {
                        sendMessage(CopyTask.MSG_FILE_SRC_CHECKSUM_FAILED, 0, 0, "(" + task.checksum + " != " + filesize + ")");
                        return false;
                    } else {
                        sendMessage(CopyTask.MSG_FILE_SIZE, 0, 0, new Long(filesize));
                    }
                    ret = copyFile(task.src, task.dst);
                    if (ret && task.checksum != 0 && task.checksum != mCurBytesCopyed) {
                        sendMessage(CopyTask.MSG_FILE_DST_CHECKSUM_FAILED, 0, 0, "(" + task.checksum + " != " + mCurBytesCopyed + ")");
                        return false;
                    }
                }
                break;
            case CopyTask.COPY_TASK_DIR: {
                    long dirsize = getDirSize(task.src);
                    if (task.checksum != 0 && task.checksum != dirsize) {
                        sendMessage(CopyTask.MSG_DIR_SRC_CHECKSUM_FAILED, 0, 0, "(" + task.checksum + " != " + dirsize + ")");
                        return false;
                    } else {
                        sendMessage(CopyTask.MSG_DIR_SIZE, 0, 0, new Long(dirsize));
                    }
                    ret = copyDir(task.src, task.dst);
                    if (ret && task.checksum != 0 && task.checksum != mCurBytesCopyed) {
                        sendMessage(CopyTask.MSG_DIR_DST_CHECKSUM_FAILED, 0, 0, "(" + task.checksum + " != " + mCurBytesCopyed + ")");
                        return false;
                    }
                    if (ret) mediaScanDir(task.dst);
                }
                break;
            case CopyTask.COPY_TASK_UNZIP:
                ret = unzipFile(task.src, task.dst);
                break;
            case CopyTask.COPY_TASK_APK:
                ret = installAllApks(task.src);
                break;
            }
            sendMessage(ret ? CopyTask.MSG_TASK_DONE : CopyTask.MSG_TASK_FAILED, 0, 0, task);
            if (mExitCopy || !ret) break;
        }
        sendMessage(ret ? CopyTask.MSG_COPY_DONE : CopyTask.MSG_COPY_FAILED, 0, 0, null);
        return ret;
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case CopyTask.MSG_COPY_START:
                mTxtStatus.setText(getString(R.string.copy_start));
                break;
            case CopyTask.MSG_COPY_DONE:
                mTxtStatus.setText(getString(R.string.copy_done));
                mTxtStatus.setTextColor(Color.rgb(0, 255, 0));
                break;
            case CopyTask.MSG_COPY_FAILED:
                mTxtStatus.setText(getString(R.string.copy_failed));
                mTxtStatus.setTextColor(Color.rgb(255, 0, 0));
                break;
            case CopyTask.MSG_TASK_TOTAL:
                mProgressMain.setMax(msg.arg1);
                break;
            case CopyTask.MSG_TASK_START: {
                    CopyTask task = (CopyTask)msg.obj;
                    String   text = "";
                    switch (task.type) {
                    case CopyTask.COPY_TASK_FILE:
                        text = getString(R.string.copy_file) + task.src + " -> " + task.dst;
                        break;
                    case CopyTask.COPY_TASK_DIR:
                        text = getString(R.string.copy_dir ) + task.src + " -> " + task.dst;
                        break;
                    }
                    mTxtMain.setText(text);
                }
                break;
            case CopyTask.MSG_TASK_DONE:
                mProgressMain.setProgress(mProgressMain.getProgress() + 1);
                break;
            case CopyTask.MSG_TASK_FAILED:
                mTxtMain.setTextColor(Color.rgb(255, 0, 0));
                break;
            case CopyTask.MSG_BYTES_COPY:
                mCurBytesCopyed += msg.arg1;
                mProgressSub.setProgress((int)(mCurBytesCopyed / 1024));
                mTxtSub.setText(getString(R.string.current_file) + (String)msg.obj);
                break;
            case CopyTask.MSG_FILE_SIZE:
            case CopyTask.MSG_DIR_SIZE: {
                    Long size = (Long)msg.obj;
                    mProgressSub.setMax((int)(size / 1024));
                    mProgressSub.setProgress(0);
                    mCurBytesCopyed = 0;
                }
                break;
            case CopyTask.MSG_FILE_FAILED:
                mTxtSub.setTextColor(Color.rgb(255, 0, 0));
                break;
            case CopyTask.MSG_FILE_SRC_CHECKSUM_FAILED:
                mTxtStatus.setText(getString(R.string.file_src_checksum_failed) + " " + (String)msg.obj);
                mTxtStatus.setTextColor(Color.rgb(255, 0, 0));
                break;
            case CopyTask.MSG_FILE_DST_CHECKSUM_FAILED:
                mTxtStatus.setText(getString(R.string.file_dst_checksum_failed) + " " + (String)msg.obj);
                mTxtStatus.setTextColor(Color.rgb(255, 0, 0));
                break;
            case CopyTask.MSG_DIR_SRC_CHECKSUM_FAILED:
                mTxtStatus.setText(getString(R.string.dir_src_checksum_failed) + " " + (String)msg.obj);
                mTxtStatus.setTextColor(Color.rgb(255, 0, 0));
                break;
            case CopyTask.MSG_DIR_DST_CHECKSUM_FAILED:
                mTxtStatus.setText(getString(R.string.dir_dst_checksum_failed) + " " + (String)msg.obj);
                mTxtStatus.setTextColor(Color.rgb(255, 0, 0));
                break;

            case CopyTask.MSG_APK_INSTALL_TOTAL:
                mTxtMain.setText(getString(R.string.installapks));
                mProgressSub.setMax(msg.arg1);
                mProgressSub.setProgress(0);
                break;
            case CopyTask.MSG_APK_INSTALL_RUNNING: {
                    String text = String.format(getString(R.string.installing), (String)msg.obj);
                    mTxtSub.setText(text);
                }
                break;
            case CopyTask.MSG_APK_INSTALL_SUCCESSED: {
                    String text = String.format(getString(R.string.installok), (String)msg.obj);
                    mTxtSub.setText(text);
                    mProgressSub.setProgress(mProgressSub.getProgress() + 1);
                }
                break;
            case CopyTask.MSG_APK_INSTALL_FAILED: {
                    String text = String.format(getString(R.string.installng), (String)msg.obj);
                    mTxtSub.setText(text);
                }
                break;
            case CopyTask.MSG_APK_INSTALL_DONE:
                mTxtSub.setText(getString(R.string.installdone));
                break;
            case CopyTask.MSG_APK_INSTALL_OBSERVER:
                synchronized (mApkInstallEvent) {
                    mApkInstallResult = msg.arg1 == 1 ? true : false;
                    mApkInstallEvent.notifyAll();
                }
                break;
            }
        }
    };
}

class CopyTask {
    public static final int MSG_COPY_START    = 0;
    public static final int MSG_COPY_DONE     = 1;
    public static final int MSG_COPY_FAILED   = 2;
    public static final int MSG_TASK_TOTAL    = 3;
    public static final int MSG_TASK_START    = 4;
    public static final int MSG_TASK_DONE     = 5;
    public static final int MSG_TASK_FAILED   = 6;
    public static final int MSG_FILE_SIZE     = 7;
    public static final int MSG_DIR_SIZE      = 8;
    public static final int MSG_BYTES_COPY    = 9;
    public static final int MSG_FILE_FAILED   = 10;
    public static final int MSG_FILE_SRC_CHECKSUM_FAILED = 11;
    public static final int MSG_FILE_DST_CHECKSUM_FAILED = 12;
    public static final int MSG_DIR_SRC_CHECKSUM_FAILED  = 13;
    public static final int MSG_DIR_DST_CHECKSUM_FAILED  = 14;

    public static final int MSG_APK_INSTALL_TOTAL    = 20;
    public static final int MSG_APK_INSTALL_RUNNING  = 21;
    public static final int MSG_APK_INSTALL_SUCCESSED= 22;
    public static final int MSG_APK_INSTALL_FAILED   = 23;
    public static final int MSG_APK_INSTALL_DONE     = 24;
    public static final int MSG_APK_INSTALL_OBSERVER = 25;

    public static final int COPY_TASK_FILE    = 0;
    public static final int COPY_TASK_DIR     = 1;
    public static final int COPY_TASK_UNZIP   = 2;
    public static final int COPY_TASK_APK     = 3;

    public int      type;
    public String    src;
    public String    dst;
    public long checksum;
}

class PackageInstallObserver extends IPackageInstallObserver.Stub {
    private Handler mHandler = null;

    public PackageInstallObserver(Handler h) {
        mHandler = h;
    }

    @Override
    public void packageInstalled(String packageName, int returnCode) {
        Message msg = new Message();
        msg.what = CopyTask.MSG_APK_INSTALL_OBSERVER;
        msg.arg1 = returnCode;
        msg.obj  = packageName;
        mHandler.sendMessage(msg);
    }
}
