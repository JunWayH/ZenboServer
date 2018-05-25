package com.zenbo.patrickc.zenboserver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
//import android.support.v7.app.AppCompatActivity;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.asus.robotframework.API.MotionControl;
import com.asus.robotframework.API.RobotCallback;
import com.asus.robotframework.API.RobotCmdState;
import com.asus.robotframework.API.RobotCommand;
import com.asus.robotframework.API.RobotErrorCode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.robot.asus.robotactivity.RobotActivity;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends RobotActivity {

    private TextView ip;
    private TextView hostIp;
    private TextView mac;
    private TextView serverState;
    private TextView receiveMessage;
    private TextView clientCountText;
    private Button refreshButton;
    private String tempString;

    //使用port 為60060
    private static int serverPort = 60060;
    private int clientCount = 0;//預設連線數為0
    private static ServerSocket serverSocket;
    public static Handler handler = new Handler();
    Thread mainServerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //findViewById
        ip = (TextView) findViewById(R.id.ipText);
        hostIp = (TextView) findViewById(R.id.ipText2);
        mac = (TextView)findViewById(R.id.macText);
        serverState = (TextView) findViewById(R.id.serverStateText);
        receiveMessage = (TextView) findViewById(R.id.receiveText);
        clientCountText = (TextView) findViewById(R.id.clientCountText);
        refreshButton = (Button) findViewById(R.id.refreshButton);

        //將ip的TextView 更改為目前的IP
        ip.setText(getWIFILocalIpAdress(this));

        //將網觀的ip 顯示出來
        hostIp.setText(getHostIp());

        //將mac的TextView更改為目前的MAC
        mac.setText(getMacAddress(this));

        //顯示當前IP 的QR CODE
        setQRCode(getWIFILocalIpAdress(this));

        //用一個 Thread 開始執行 主要的 Socket Server
        displayToast("Server Start");
        mainServerThread = new Thread(runServer);
        mainServerThread.start();

        //重新整理IP與client連線狀態
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                //將ip的TextView 更改為目前的IP
                ip.setText(getWIFILocalIpAdress(MainActivity.this));

                //將網觀的ip 顯示出來
                hostIp.setText(getHostIp());

                //將mac的TextView更改為目前的MAC
                mac.setText(getMacAddress(MainActivity.this));

                //顯示當前IP 的QR CODE
                setQRCode(getWIFILocalIpAdress(MainActivity.this));
                displayToast("重新整理");

            }
        });

    }

    //在activity關閉時會去調用
    protected void onDestroy(){
        super.onDestroy();
        if (this.mainServerThread != null)
            // 如果主要的thread還沒被關掉，則關掉它。
            this.mainServerThread.interrupt();
    }

    //用來得到 Mac Address
    public static String getMacAddress(Context mContext) {
        String macStr;
        WifiManager wifiManager = (WifiManager) mContext
                .getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo.getMacAddress() != null) {
            macStr = wifiInfo.getMacAddress();// MAC地址
        } else {
            macStr = "Can not get Mac Address.";
        }

        return macStr;
    }

    //用來得到 IP Address
    public static String getWIFILocalIpAdress(Context mContext) {

        //取得WIFI服務
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        //判斷wifi是否開啟
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipAddress = wifiInfo.getIpAddress();
        String ip = formatIpAddress(ipAddress);
        return ip;
    }

    //將IP格式化
    private static String formatIpAddress(int ipAdress) {

        return (ipAdress & 0xFF) + "." +
                ((ipAdress >> 8) & 0xFF) + "." +
                ((ipAdress >> 16) & 0xFF) + "." +
                (ipAdress >> 24 & 0xFF);
    }

    //獲取網關的ip
    public static String getHostIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface
                    .getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> ipAddr = intf.getInetAddresses(); ipAddr
                        .hasMoreElements();) {
                    InetAddress inetAddress = ipAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        return inetAddress.getHostAddress();

                    }
                }
            }
        }
        catch (SocketException ex) {

        }
        catch (Exception e) {

        }
        return null;
    }

    //顯示當前 IP 的 QR Code
    public void setQRCode(String ip) {
        ImageView qrCode = (ImageView) findViewById(R.id.qrCode);

        BarcodeEncoder encoder = new BarcodeEncoder();
        try {
            Bitmap bit = encoder.encodeBitmap(ip, BarcodeFormat.QR_CODE,
                    400, 400);
            qrCode.setImageBitmap(bit);
        } catch (WriterException e) {
            e.printStackTrace();
        }

    }

    //更新從Client收到的訊息，並且顯示在layout上
    private Runnable updateReceiveMessage = new Runnable() {
        @Override
        public void run() {
            receiveMessage.setText(tempString);
        }
    };

    //[以下三點為Socket Server，可支援多個Client連線]

    //1. 啟動Server 並且開始聆聽是否有Client要接入
    private Runnable runServer = new Runnable() {
        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(serverPort);
                //利用handler 去使用 Runnable 的物件來更改連線狀態的文字為Server On
                handler.post(new setServerState(true));

                while (!serverSocket.isClosed()) {

                    // 呼叫等待接受客戶端連接
                    waitNewClient();
                }
            } catch (IOException e) {
                e.getStackTrace();
            }

        }
    };

    //2. 等待接受客戶端連接
    public void waitNewClient() {
        try {
            //接到了就新建一個 socket 連線
            Socket socket = serverSocket.accept();

            // 創造新的連接
            createNewClient(socket);

        } catch (IOException e) {
            e.getStackTrace();
        }

    }

    //3. 創造新的使用者連接，並且處理接收訊息
    public void createNewClient(final Socket socket) throws IOException {
        //更改連線client 連線狀態
        handler.post(new setClientCount(true));//更改Client連線數量+1
        displayToast("New Client Joined!");//顯示吐司

        // 以新的執行緒來執行
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    // 取得網路串流
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    // 當Socket已連接時連續執行

                    while (socket.isConnected()) {
                        // 取得網路串流的訊息
                        String msg = br.readLine();

                        //如果訊息為空代表斷線，有新的一行則是代表有新的輸入
                        if (msg == null) {
                            //修改狀態為斷線
                            displayToast("Offline");//顯示吐司
                            System.out.println("Offline");//輸出在Android Studio Console畫面上
                            break;
                        }

                        //訊息不為空則使用 callZenboAPI() 來處理收到的訊息
                        callZenboAPI(msg);//如果是一般訊息則顯示在畫面上，此外則
                    }

                    //跳出迴圈則代表斷線
                    handler.post(new setClientCount(false));//更改Client連線數量-1
                    displayToast("disconnect and break");//顯示吐司

                } catch (IOException e) {
                    e.getStackTrace();
                }
            }
        });

        // 啟動執行緒
        t.start();
    }

    //用來判斷是否是要使用Zenbo的API，如果是則去調用 ZenboAPI
    public void callZenboAPI(String message){

        switch (message){
            case "FORWARD":
                robotAPI.motion.remoteControlBody(MotionControl.Direction.Body.FORWARD);
                break;
            case "BACKWARD":
                robotAPI.motion.remoteControlBody(MotionControl.Direction.Body.BACKWARD);
                break;
            case "TURN_RIGHT" :
                robotAPI.motion.remoteControlBody(MotionControl.Direction.Body.TURN_RIGHT);
                break;
            case "TURN_LEFT" :
                robotAPI.motion.remoteControlBody(MotionControl.Direction.Body.TURN_LEFT);
                break;
            case "BodyStop" :
                robotAPI.motion.remoteControlBody(MotionControl.Direction.Body.STOP);
                break;
            case "UP" :
                robotAPI.motion.remoteControlHead(MotionControl.Direction.Head.UP);
                break;
            case "DOWN" :
                robotAPI.motion.remoteControlHead(MotionControl.Direction.Head.DOWN);
                break;
            case "RIGHT" :
                robotAPI.motion.remoteControlHead(MotionControl.Direction.Head.RIGHT);
                break;
            case "LEFT" :
                robotAPI.motion.remoteControlHead(MotionControl.Direction.Head.LEFT);
                break;
            case "HeadStop" :
                robotAPI.motion.remoteControlHead(MotionControl.Direction.Head.STOP);
                break;
            default:
                //沒有宣告在上面的訊息，則直接用吐司輸出訊息，並且顯示在畫面上。
                tempString = message;//先將訊息裝進全域變數tempString，updateReceiveMessage() method 會去使用tempString來更改layout上的文字
                handler.post(updateReceiveMessage);//利用handler去調用updateReceiveMessage更改版面上的文字
                displayToast(message);//顯示吐司
                break;
        }

    }

    //用來顯示吐司的 method
    public void displayToast(String toastString){
        Message msg = new Message();
        msg.obj = toastString;
        toastHandler.sendMessage(msg);
    }
    //用來顯示吐司的Handler
    Handler toastHandler = new Handler(){
        public void handleMessage(Message msg) {
            String mString=(String)msg.obj;
            Toast.makeText(getApplicationContext(), mString, Toast.LENGTH_SHORT).show();
        }
    };

    //用來更改伺服器運作狀態的文字 true為已上線運作
    private class setServerState implements Runnable {

        boolean isConnected;

        setServerState(boolean isConnected) {
            this.isConnected = isConnected;
        }

        @Override
        public void run() {
            if (isConnected) {
                //如果現在Server有開啟，則顯示綠色 Server On
                serverState.setText("Server On");
                serverState.setTextColor(Color.parseColor("#66ff33"));
            } else {
                serverState.setText("Server Off");
                serverState.setTextColor(Color.parseColor("#ff0066"));
            }
        }
    }

    //用來更改客戶端連線數量的文字 參數true為多一個Client連線
    private class setClientCount implements Runnable {

        boolean isConnected;

        setClientCount(boolean isConnected) {
            this.isConnected = isConnected;
        }

        @Override
        public void run() {

            if (isConnected) {
                clientCount ++;//client 連線數量+1
                clientCountText.setText(String.valueOf(clientCount));
                clientCountText.setTextColor(Color.parseColor("#66ff33"));
            } else {
                clientCount --;//client 連線數量-1
                clientCountText.setText(String.valueOf(clientCount));
                clientCountText.setTextColor(Color.parseColor("#ff0066"));
            }
        }
    }

    //下面一堆是使用 Zenbo SDK 必加的東東 ------------------------------------------
    public static RobotCallback robotCallback = new RobotCallback() {
        @Override
        public void onResult(int cmd, int serial, RobotErrorCode err_code, Bundle result) {
            super.onResult(cmd, serial, err_code, result);

            Log.d("ZenboServer", "onResult:"
                    + RobotCommand.getRobotCommand(cmd).name()
                    + ", serial:" + serial + ", err_code:" + err_code
                    + ", result:" + result.getString("RESULT"));
        }

        @Override
        public void onStateChange(int cmd, int serial, RobotErrorCode err_code, RobotCmdState state) {
            super.onStateChange(cmd, serial, err_code, state);
        }
    };

    public static RobotCallback.Listen robotListenCallback = new RobotCallback.Listen() {
        @Override
        public void onFinishRegister() {

        }

        @Override
        public void onVoiceDetect(JSONObject jsonObject) {

        }

        @Override
        public void onSpeakComplete(String s, String s1) {

        }

        @Override
        public void onEventUserUtterance(JSONObject jsonObject) {

        }

        @Override
        public void onResult(JSONObject jsonObject) {

        }

        @Override
        public void onRetry(JSONObject jsonObject) {

        }
    };
    //上面一堆是使用 Zenbo SDK 必加的東東 ------------------------------------------

    //使用 使用
    public MainActivity() {
        super(robotCallback, robotListenCallback);
    }
}

