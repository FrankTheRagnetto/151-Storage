package com.the151universe.fintochat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;

/** Scrive un allegato nella propria cache e lo apre con 151 Viewer, come fa un'app di chat. */
public class Main extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            File f = new File(getCacheDir(), "allegato-chat.txt");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write("Allegato di prova di FintoChat.\nQuesto file NON deve poter essere cancellato da 151 Viewer.\n".getBytes("UTF-8"));
            }
            Uri u = Uri.parse("content://com.the151universe.fintochat.provider/media/allegato-chat.txt");
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "text/plain")
                .setClassName("com.the151universe.viewer", "com.the151universe.viewer.ViewerActivity")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.e("FintoChat", "errore", e);
        }
        finish();
    }
}
