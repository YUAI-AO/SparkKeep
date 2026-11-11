package com.spark.keep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：设备重启后自动把后台服务拉起来。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Spark sp = Spark.get(context);
            if (!sp.store.all().optBoolean("boot_start", true)) return;
            sp.log("sys", "收到开机广播，启动后台");
            SparkService.start(context, true);
        } catch (Exception ignored) {
        }
    }
}
