package socks;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.xuebao.rtmpPush.CameraPublishActivity;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.Lock;

import android_serialport_api.ComPort;

//连接应用服务器的类。包含接收 心跳 和重连
//20180412 透传心跳消息 并且不再自己启动心跳线程
public class SockAPP {

    private static final String TAG = "SockAPP";

    public String hostName = "192.168.4.1";
    public int port = 80;
    private Socket socket = null;
    private Handler handler;

    Thread thSocket = null;
    //Thread thHearbeatTimer = null;

    private boolean ShouldStopNow = false;
    byte heart_beat_msg[] = new byte[21];

    public SockAPP() {
    }

    public void StopNow() {
        ShouldStopNow = true;

        if (socket != null) {
            try {
                socket.close();
                socket = null;
            } catch (IOException e) {
            }
        }
        if (thSocket != null) {
            thSocket.interrupt();
            thSocket = null;
        }

       //if (thHearbeatTimer != null) {
       //     thHearbeatTimer.interrupt();
       //     thHearbeatTimer = null;
       // }
    }

    public void CloseFireRetry()
    {
        if (socket != null) {
            try {
                socket.close();
                socket = null;
            } catch (IOException e) {
            }
        }
    }

   /* void FireHeadbeat() {
        if (thHearbeatTimer != null) {
            thHearbeatTimer.interrupt();
            thHearbeatTimer = null;
        }

        thHearbeatTimer = new Thread(new Runnable() {
            @Override
            public void run() {
                while (ShouldStopNow == false) {
                    if (ShouldStopNow == true) {
                        break;
                    }

                    if( socket!= null )
                    {
                        //Log.e(TAG, "心跳");
                        sendMsg(heart_beat_msg);

                        Message me1 = Message.obtain();//心跳消息
                        me1.what = CameraPublishActivity.MessageType.msgMyFireHeartBeat.ordinal();
                        if (handler != null) handler.sendMessage(me1);
                    }

                    if (ShouldStopNow == true) {
                        break;
                    }

                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        continue;
                    }
                }

                Log.e(TAG, "心跳线程退出");
            }
        });
        thHearbeatTimer.start();
    }*/

    public void StartWokring(Handler handler, String strHost, int dport)//connect and recv
    {
        this.handler = handler;
        hostName = strHost;
        port = dport;

        ShouldStopNow = false;

        heart_beat_msg[0] = (byte) 0xfe;
        heart_beat_msg[1] = (byte) (0);
        heart_beat_msg[2] = (byte) (0);
        heart_beat_msg[3] = (byte) ~heart_beat_msg[0];
        heart_beat_msg[4] = (byte) ~heart_beat_msg[1];
        heart_beat_msg[5] = (byte) ~heart_beat_msg[2];
        heart_beat_msg[6] = (byte) (heart_beat_msg.length);
        heart_beat_msg[7] = (byte) 0x35;

        String strMAC = VideoConfig.instance.getMac();
        System.arraycopy(strMAC.getBytes(), 0, heart_beat_msg, 8, strMAC.getBytes().length);

        int total_c = 0;
        for (int i = 6; i < heart_beat_msg.length - 1; i++) {
            total_c += (heart_beat_msg[i] & 0xff);
        }
        heart_beat_msg[heart_beat_msg.length - 1] = (byte) (total_c % 100);

        thSocket = new Thread(new ReceiveWatchDog());
        thSocket.start();

       // FireHeadbeat();
    }

    public void ApplyNewServer(String strHost, int dport) {
        if (hostName.equals(strHost) && port == dport)
            return;

        hostName = strHost;
        port = dport;

        try {
            if(socket != null)
            {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {

        }
    }

    class ReceiveWatchDog implements Runnable {

        String readBuffer = "";


        protected void onDataReceived(byte[] buffer, int size) {
            readBuffer = readBuffer + ComPort.bytes2HexString(buffer, size);

            //开头可能就不正确
            if (readBuffer.contains("FE")) {
                readBuffer = readBuffer.substring(readBuffer.indexOf("FE"));
            } else {
                readBuffer = "";
                if(CameraPublishActivity.DEBUG)  Log.e("~~~~", "data begin error. readBuffer = kong ");
            }

            //指令 至少是9位 包长度在第 7位
            while (readBuffer.length() >= 9 * 2) {
                String slen = readBuffer.substring(12, 14);
                int len = Integer.parseInt(slen, 16);

                //包长度最大50
                if (len > 50) {
                    //包长度出错 应该是数据干扰
                    if(CameraPublishActivity.DEBUG)  Log.e("~~~", "packet len error");
                    //丢弃这条指令
                    readBuffer = readBuffer.substring(2);
                    if (readBuffer.contains("FE")) {
                        readBuffer = readBuffer.substring(readBuffer.indexOf("FE"));
                    } else {
                        readBuffer = "";
                        if(CameraPublishActivity.DEBUG)  Log.e("~~~~", "packet len error readBuffer = kong ");
                    }
                    continue;
                }

                if (readBuffer.length() >= len * 2) {
                    String sBegin = readBuffer.substring(0, 2);
                    if (sBegin.equals("FE")) {
                        //开头正确
                        String msgContent = readBuffer.substring(0, len * 2);
                        //校验指令
                        if (ComPort.check_com_data_string(msgContent, len * 2)) {
                            if(CameraPublishActivity.DEBUG)  Log.e("net recv",  msgContent);
                            readBuffer = readBuffer.substring(len * 2);

                            if( CameraPublishActivity.mainInstance != null)
                                CameraPublishActivity.mainInstance.ThreadHandleSockData( ComPort.hexStringToBytes(msgContent), len );

                        } else {
                            //指令不正确
                            if(CameraPublishActivity.DEBUG)  Log.e("cmd error", msgContent + "***" + readBuffer);
                            readBuffer = readBuffer.substring(2);
                            if (readBuffer.contains("FE")) {
                                readBuffer = readBuffer.substring(readBuffer.indexOf("FE"));
                            } else {
                                readBuffer = "";
                                if(CameraPublishActivity.DEBUG)  Log.e("~~~~", "cmd error. can't find FE readBuffer = kong ");
                            }
                        }
                    } else {
                        //开头不正确
                        if(CameraPublishActivity.DEBUG)  Log.e("data begin error.", readBuffer);
                        if (readBuffer.contains("FE")) {
                            readBuffer = readBuffer.substring(readBuffer.indexOf("FE"));
                        } else {
                            readBuffer = "";
                            if(CameraPublishActivity.DEBUG)  Log.e("~~~~", "data begin error,can't find FE readBuffer = kong ");
                        }
                    }
                } else {
                    //等下一次接
                    //if(CameraPublishActivity.DEBUG)  Log.e("不够数", "等待" + readBuffer);
                    break;
                }
            }
        }

        @Override
        public void run() {
            while (ShouldStopNow == false) {
                if (hostName.equals("") || port == 0)//参数不对。空循环
                {
                    try {
                        Thread.sleep(3000);
                        continue;
                    } catch (InterruptedException ea) {
                        continue;
                    }
                } else {
                    try {
                        if(CameraPublishActivity.DEBUG)  Log.e(TAG, "try connect====IP:" + hostName + "Port:" + Integer.toString(port));

                        String strdb = String.format("Connecting :%s Port:%d",hostName,  port);
                        Message mdb = Message.obtain();
                        mdb.what = CameraPublishActivity.MessageType.msgOutputLog.ordinal();
                        mdb.obj = strdb;
                        if (handler != null) handler.sendMessage(mdb);

                        ShouldStopNow = false;
                        InetAddress addr = InetAddress.getByName(hostName);
                        String domainName = addr.getHostName();//获得主机名
                        String ServerIP = addr.getHostAddress();//获得IP地址

                        socket = new Socket(ServerIP, port);
                        socket.setKeepAlive(true);
                        socket.setSoTimeout(20000);//设置读操作超时时间20 s
                    } catch (IOException e) {
                        if(CameraPublishActivity.DEBUG)  Log.e(TAG, "Connect excetion..retry after 3s");
                        try {
                            Thread.sleep(3000);
                            continue;
                        } catch (InterruptedException ea) {
                            continue;
                        }
                    }
                }

                //立即心跳一次
                sendMsg(heart_beat_msg);
                while (socket != null && socket.isConnected() && ShouldStopNow == false) {
                    try {
                        InputStream reader = socket.getInputStream();

                        byte data_buff[] = new byte[1024];
                        int recv_len = reader.read(data_buff, 0, 1024);
                        if (recv_len > 0) {
                            onDataReceived(data_buff, recv_len);
                        } else {
                            if(CameraPublishActivity.DEBUG)  Log.e(TAG, "recv len<=0.disconnect");
                            if( socket != null)
                            { socket.close();
                            socket = null;}
                            break;
                        }
                    }
                    catch(SocketTimeoutException s) {
                        Log.e(TAG,"read timeout.send packet to confirm");
                        String strdb = String.format("read timeout.send packet to confirm");
                        Message mdb = Message.obtain();
                        mdb.what = CameraPublishActivity.MessageType.msgOutputLog.ordinal();
                        mdb.obj = strdb;
                        if (handler != null) handler.sendMessage(mdb);

                        Message mdq = Message.obtain();
                        mdq.what = CameraPublishActivity.MessageType.msgQueryNetworkState.ordinal();
                        mdq.obj = strdb;
                        if (handler != null) handler.sendMessage(mdq);

                       /* try {
                            if( socket != null){socket.close();
                                socket = null;}
                        } catch (IOException aa) {
                            break;
                        }
                        break;*/
                    }
                    catch (IOException ea) {
                       // ea.printStackTrace();
                        Log.e(TAG, "read exception.disconnect.retry。");
                        String strdb = String.format("read exception.disconnect.retry。");
                        Message mdb = Message.obtain();
                        mdb.what = CameraPublishActivity.MessageType.msgOutputLog.ordinal();
                        mdb.obj = strdb;
                        if (handler != null) handler.sendMessage(mdb);
                        if( ShouldStopNow == false ) {
                            try {
                                if( socket != null){socket.close();
                                    socket = null;}
                            } catch (IOException aa) {
                                break;
                            }
                        }
                    }
                }
            }

            if(CameraPublishActivity.DEBUG)  Log.e(TAG, "recv thread exit.");
        }
    }

    public boolean  heartBeat()
    {
       return  sendMsg(heart_beat_msg);
    }

    public boolean sendMsg(byte[] msg) {
        synchronized (this) {
            if (socket == null) {
                if(CameraPublishActivity.DEBUG) Log.e(TAG, "socket is null,not send");
                return false;
            }

            try {
                if (socket != null && socket.isConnected()) {
                    OutputStream outputStream = socket.getOutputStream();
                    outputStream.write(msg);
                    outputStream.flush();

                    return true;
                } else {
                    if(CameraPublishActivity.DEBUG)   Log.e(TAG, "socket is not connected.not send");
                    return false;
                }
            } catch (IOException e) {
                // FireReconnect();
                Log.e(TAG,"send exception.");
            }
        }

        return  false;
    }

    public static final String bytesToHexString(byte[] buffer) {
        StringBuffer sb = new StringBuffer(buffer.length);
        String temp;

        for (int i = 0; i < buffer.length; ++i) {
            temp = Integer.toHexString(0xff & buffer[i]);
            if (temp.length() < 2)
                sb.append(0);

            sb.append(temp);
        }

        return sb.toString();
    }
}
