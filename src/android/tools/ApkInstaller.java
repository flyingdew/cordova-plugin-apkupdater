package de.kolbasa.apkupdater.tools;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.WindowManager;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import de.kolbasa.apkupdater.exceptions.InstallationFailedException;

public class ApkInstaller {

    private static boolean isFullScreen(Context context) {
        return (((Activity) context).getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
    }

    public static void install(Context context, File update) throws IOException {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!isFullScreen(context)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            String fileProvider = context.getPackageName() + ".apkupdater.provider";
            intent.setData(FileProvider.getUriForFile(context, fileProvider, update));
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            File newPath = new File(context.getExternalCacheDir(), update.getName());
            FileTools.copy(update, newPath);
            intent.setDataAndType(Uri.fromFile(newPath), "application/vnd.android.package-archive");
        }
        context.startActivity(intent);
    }

    public static void rootInstall(Context context, File update) throws InstallationFailedException, IOException, InterruptedException {
        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        String mainActivity = launchIntent.getComponent().getClassName();

        // -r Reinstall if needed
        // -d Downgrade if needed
        String command = "pm install -r -d " + update.getAbsolutePath() +
                " && am start -n " + packageName + "/" + mainActivity;

        Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        StringBuilder builder = new StringBuilder();

        BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String s;
        while ((s = stdOut.readLine()) != null) {
            builder.append(s);
        }
        BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        while ((s = stdError.readLine()) != null) {
            builder.append(s);
        }

        process.waitFor();
        process.destroy();

        stdOut.close();
        stdError.close();

        if (builder.length() > 0) {
            throw new InstallationFailedException(builder.toString());
        }
    }

}
