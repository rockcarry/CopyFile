package com.android.copyfile;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.Log;

import java.io.*;
import java.util.*;

public class MainActivity extends Activity
{
    private static final String TAG = "CopyFile";

    private TextView    mTxtStatus;
    private TextView    mTxtMain;
    private TextView    mTxtSub;
    private ProgressBar mProgressMain;
    private ProgressBar mProgressSub;
    private Thread      mThread;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        mTxtStatus    = (TextView)findViewById(R.id.txt_status );
        mTxtMain      = (TextView)findViewById(R.id.txt_main   );
        mTxtSub       = (TextView)findViewById(R.id.txt_sub    );
        mProgressMain = (ProgressBar)findViewById(R.id.bar_main);
        mProgressSub  = (ProgressBar)findViewById(R.id.bar_sub );
        mTxtStatus.setText("mTxtStatus");
        mTxtMain  .setText("mTxtMain"  );
        mTxtSub   .setText("mTxtSub"   );

        mThread = new Thread() {
            @Override
            public void run() {
                doCopyFile(mHandler);
                mThread = null;
            }
        };
        mThread.start();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
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

    private static final String COPYFILE_CONFIG_PATH = "/mnt/extsd/copyfile/config.ini";
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

    private boolean copyFile(String src, String dst) {
        Log.d(TAG, "copyfile: " + src + " -> " + dst);
        File fsrc = new File(src);
        File fdst = new File(dst);
        InputStream  is = null;
        OutputStream os = null;
        boolean     ret = false;
        byte[] buf = new byte[1024];
        try {
            is = new FileInputStream (fsrc);
            os = new FileOutputStream(fdst);
            int bytesRead;
            while ((bytesRead = is.read(buf)) > 0) {
                os.write(buf, 0, bytesRead);
            }
            ret = true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) is.close();
                if (os != null) os.close();
            } catch (Exception e) {}
        }
        return ret;
    }

    private boolean copyDir(String src, String dst) {
        Log.d(TAG, "copydir: " + src + " -> " + dst);
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

    private boolean installApk(String src) {
        return false;
    }

    private boolean doCopyFile(Handler handler) {
        handler.sendEmptyMessage(CopyTask.MSG_COPY_START);
        ArrayList<CopyTask> list = parseCopyFileConfig(COPYFILE_CONFIG_PATH);
        for (CopyTask task : list) {
            switch (task.type) {
            case CopyTask.COPY_TASK_FILE:
                copyFile(task.src, task.dst);
                break;
            case CopyTask.COPY_TASK_DIR:
                copyDir(task.src, task.dst);
                break;
            case CopyTask.COPY_TASK_UNZIP:
                unzipFile(task.src, task.dst);
                break;
            case CopyTask.COPY_TASK_APK:
                installApk(task.src);
                break;
            }
        }
        handler.sendEmptyMessage(CopyTask.MSG_COPY_DONE);
        return true;
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
                break;
            case CopyTask.MSG_COPY_FAILED:
                mTxtStatus.setText(getString(R.string.copy_failed));
                break;
            }
        }
    };
}

class CopyTask {
    public static final int MSG_COPY_START    = 0;
    public static final int MSG_COPY_DONE     = 1;
    public static final int MSG_COPY_FAILED   = 2;
    public static final int MSG_MAIN_PROGRESS = 3;
    public static final int MSG_SUB_PROGRESS  = 4;
    public static final int MSG_TOTAL_TASKS   = 5;
    public static final int MSG_FILE_SIZE     = 6;
    public static final int MSG_DIR_SIZE      = 7;
    public static final int MSG_APK_NUM       = 8;

    public static final int COPY_TASK_FILE    = 0;
    public static final int COPY_TASK_DIR     = 1;
    public static final int COPY_TASK_UNZIP   = 2;
    public static final int COPY_TASK_APK     = 3;

    public int   type;
    public String src;
    public String dst;
}


