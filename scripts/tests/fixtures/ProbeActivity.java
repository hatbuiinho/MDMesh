package com.mdmesh.probe;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.net.*;
import java.io.*;
/** Test fixture only: separate UIDs exercise real VPN owner lookup, not mocked policy calls. */
public class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String mode=getIntent().getStringExtra("mode");
        new Thread(()->{
            try {
                if("system".equals(mode)) {
                    boolean blocked;
                    try { InetAddress.getAllByName("example.com"); blocked=false; } catch(UnknownHostException e) { blocked=true; }
                    Log.i("MDMeshProbe","RESULT "+getPackageName()+" blocked="+blocked);
                } else {
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    DataOutputStream out=new DataOutputStream(bytes);
                    out.writeShort(0x1234);out.writeShort(0x0100);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(0);
                    for(String label:"example.com".split("\\.")){out.writeByte(label.length());out.writeBytes(label);}
                    out.writeByte(0);out.writeShort(1);out.writeShort(1);
                    byte[] query=bytes.toByteArray();
                    try(DatagramSocket socket=new DatagramSocket()) {
                        socket.setSoTimeout(8000);socket.connect(InetAddress.getByName("10.253.0.2"),53);
                        socket.send(new DatagramPacket(query,query.length));
                        byte[] response=new byte[4096];DatagramPacket reply=new DatagramPacket(response,response.length);socket.receive(reply);
                        Log.i("MDMeshProbe","RESULT "+getPackageName()+" rcode="+(response[3]&15));
                    }
                }
            }catch(Exception e){Log.i("MDMeshProbe","RESULT "+getPackageName()+" error="+e.getClass().getSimpleName()+":"+e.getMessage());}
            runOnUiThread(()->finish());
        }).start();
    }
}
