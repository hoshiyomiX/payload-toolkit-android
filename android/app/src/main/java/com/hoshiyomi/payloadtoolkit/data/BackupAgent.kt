package com.hoshiyomi.payloadtoolkit.data

import android.app.backup.BackupAgentHelper
import android.app.backup.FileBackupHelper

class BackupAgent : BackupAgentHelper() {
    override fun onCreate() {
        addHelper("payload_files", FileBackupHelper(this,
            "../files/input",
            "../files/output",
            "../files/keys"
        ))
    }
}
