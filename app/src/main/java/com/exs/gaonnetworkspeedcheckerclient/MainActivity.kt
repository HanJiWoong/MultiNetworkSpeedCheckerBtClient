package com.exs.gaonnetworkspeedcheckerclient

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.afra55.speedometer.SpeedometerDialog
import com.example.bluetoothClient.activity.ScanActivity
import com.example.bluetoothClient.net.BTConstant.BT_REQUEST_ENABLE
import com.example.bluetoothClient.net.BluetoothClient
import com.example.bluetoothClient.net.SocketListener
import com.exs.gaonnetworkspeedcheckerclient.net.Ping
import com.facebook.network.connectionclass.ConnectionClassManager
import com.facebook.network.connectionclass.DeviceBandwidthSampler
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity() {
    private  lateinit var mContext:Context

    private var handler: Handler = Handler()
    private var sbLog = ""
    private lateinit var btClient : BluetoothClient

    private lateinit var svLogView: ScrollView
    private lateinit var tvLogView: TextView
    private lateinit var etMessage: EditText

    private var mBeforePingCnt = 0

    private lateinit var mSpeedometer:SpeedometerDialog
    private lateinit var mTVDownload:TextView
    private lateinit var mTVUpload:TextView
    private lateinit var mTVDelayTime:TextView
    private lateinit var mTVJitter:TextView

    private var isConnected:Boolean = false

    //********************************************************
    // Web
    // *******************************************************

    private lateinit var mWVSpeedCheck:WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkPermission()
        }

        btClient = BluetoothClient(this)
        AppController.Instance.init(this, btClient)

        initUI()
//        initWebView()
        setListener()


        btClient.setOnSocketListener(mOnSocketListener)
    }



    private fun initUI() {
        mContext = this

        svLogView = findViewById(R.id.svLogView)
        tvLogView = findViewById(R.id.tvLogView)
        etMessage = findViewById(R.id.etMessage)

        mSpeedometer = findViewById(R.id.test_speedometer)
        mTVDownload = findViewById(R.id.TVValueDownload)
        mTVUpload = findViewById(R.id.TVValueUpload)
        mTVDelayTime = findViewById(R.id.TVValueLateTime)
        mTVJitter = findViewById(R.id.TVValueZitter)

        mSpeedometer.setLimitNumber(3000)
        mSpeedometer.setMaxNumber(3000F)

    }

    private fun setValueInUI(downSpeed:Int, upSpeed:Int, delayTime:Int, jitter:Int) {
        runOnUiThread {
            val resultDownSpeed: Float = (downSpeed / 1000).toFloat()
            val resultUpSpeed: Float = (upSpeed / 1000).toFloat()
            var resultJitter: Int = jitter
            if (resultJitter < 0) {
                resultJitter *= -1
            }


            mSpeedometer.setCurrentNumber(resultDownSpeed)

            mTVDownload.text = "${resultDownSpeed} Mbps"
            mTVUpload.text = "${resultUpSpeed} Mbps"
            mTVDelayTime.text = "${delayTime} ms"
            mTVJitter.text = "${resultJitter} ms"

        }
    }

    private fun setSpeedometorValue(downTemp:Int,upTemp:Int) {

        runOnUiThread {
            val resultDownSpeed: Float = (downTemp / 1000).toFloat()
            val resultUpSpeed:Float = (upTemp / 1000).toFloat()
            mSpeedometer.setCurrentNumber(resultDownSpeed)

            mTVDownload.text = "${resultDownSpeed} Mbps"
            mTVUpload.text = "${resultUpSpeed} Mbps"
        }
    }

    private fun setListener() {
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            ScanActivity.startForResult(this, 102)
        }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            btClient.disconnectFromServer()
            sendTestThread.actionStop()
            sendTestThread = SendTestThread()
        }

        findViewById<Button>(R.id.btnSendData).setOnClickListener {
            if (etMessage.text.toString().isNotEmpty() && isConnected) {

                //etCommand.setText("")
                sendTestThread.setSendText(etMessage.text.toString())

                if (!sendTestThread.isRunning()) {
                    sendTestThread.start()
                }

                btClient.sendData(etMessage.text.toString())

            }
        }

        ConnectionClassManager.getInstance().register {

        }

        DeviceBandwidthSampler.getInstance().startSampling()

    }


    inner class SendTestThread : Thread() {
        private var isRun: Boolean = false
        private var sendString: String? = null

        override fun run() {
            while (isRun) {
                println("Thread :: ${Thread.currentThread().name}")


                val cm: ConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val netInfo: NetworkInfo? = cm.getActiveNetworkInfo()
                val nc: NetworkCapabilities? = cm.getNetworkCapabilities(cm.getActiveNetwork())
                var downSpeed: Int = nc?.getLinkDownstreamBandwidthKbps() ?: -1;
                var upSpeed: Int = nc?.getLinkUpstreamBandwidthKbps() ?: -1;


                downSpeed = (downSpeed * 0.85).toInt()
                upSpeed = (upSpeed * 0.75).toInt()

                sendString?.let {
                    if(it.contains("lte")) {
                        downSpeed = downSpeed * 3 * 5
                        upSpeed = upSpeed * 3 * 2
                    } else if (it.contains("5g")) {
                        downSpeed = downSpeed * 2
                        upSpeed = upSpeed * 2
                    }
                }

                val ping:Ping = Ping.ping(URL("http://www.google.com:443/"), mContext)
                val jitter:Int = mBeforePingCnt - ping.cnt
                mBeforePingCnt = ping.cnt

                Log.e("Speed Test","host : ${ping.host} , ip:${ping.ip} , net:${ping.net} , dns:${ping.dns}ms, cnt:${ping.cnt}ms")

                Log.e("Speed Test","Down : ${downSpeed}kbps, Up : ${upSpeed}kbps")

                setValueInUI(downSpeed,upSpeed,ping.cnt,jitter)
                sendString?.let { btClient.sendData("${it}::${downSpeed}::${upSpeed}::${ping.cnt}::${jitter}") }
                SystemClock.sleep(250)

                val tempDown:Int = downSpeed - (0..downSpeed/3).random()
                val tempUp:Int = upSpeed - (0..upSpeed/3).random()

                setSpeedometorValue(tempDown,tempUp)
                SystemClock.sleep(250)
            }
        }

        override fun start() {
            isRun = true
            super.start()
        }

        fun setSendText(sendStr: String) {
            sendString = sendStr
        }

        fun actionStop() {
            isRun = false
        }

        fun isRunning(): Boolean {
            return isRun
        }
    }

    var sendTestThread: SendTestThread = SendTestThread()


    private fun log(message: String) {

        var temp = sbLog.split("\n")

        if (temp.count() > 7) {
            sbLog = ""
            var iter = 0
            for (str in temp) {
                if (iter != 0) {
                    if(!sbLog.isEmpty()) {
                        sbLog = sbLog + "\n" + str
                    } else {
                        sbLog = str
                    }
                }

                iter++
            }
        }

        if(!sbLog.isEmpty()) {
            sbLog = sbLog + "\n" + message.trimIndent()
        } else {
            sbLog = message.trimIndent()
        }
        handler.post {
            tvLogView.text = sbLog.toString()
            svLogView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }


    private val mOnSocketListener: SocketListener = object : SocketListener {
        override fun onConnect() {
            log("Connect!\n")
            isConnected = true
        }

        override fun onDisconnect() {
            log("Disconnect!\n")
            isConnected = false
        }

        override fun onError(e: Exception?) {
            e?.let { log(e.toString() + "\n") }
        }

        override fun onReceive(msg: String?) {
            msg?.let { log("Receive : $it\n") }
        }

        override fun onSend(msg: String?) {
            msg?.let { log("Send : $it\n") }
        }

        override fun onLogPrint(msg: String?) {
            msg?.let { log("$it\n") }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            BT_REQUEST_ENABLE -> if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(applicationContext, "블루투스 활성화", Toast.LENGTH_LONG).show()
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(applicationContext, "취소", Toast.LENGTH_LONG).show()
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun checkPermission() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )

        for (permission in permissions) {
            val chk = checkCallingOrSelfPermission(Manifest.permission.WRITE_CONTACTS)
            if (chk == PackageManager.PERMISSION_DENIED) {
                requestPermissions(permissions, 0)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            for (element in grantResults) {
                if (element == PackageManager.PERMISSION_GRANTED) {
                } else {
                    TedPermission(this)
                        .setPermissionListener(object : PermissionListener {
                            override fun onPermissionGranted() {

                            }

                            override fun onPermissionDenied(deniedPermissions: ArrayList<String?>) {

                            }
                        })
                        .setDeniedMessage("You have permission to set up.")
                        .setPermissions(
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_ADMIN
                        )
                        .setGotoSettingButton(true)
                        .check();
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        AppController.Instance.bluetoothOff()
        if(sendTestThread.isRunning()) sendTestThread.actionStop()
    }

    //********************************************************
    // Web
    // *******************************************************
    fun initWebView() {
        mWVSpeedCheck = findViewById(R.id.WVSpeedCheck)
        mWVSpeedCheck.settings.javaScriptEnabled = true

        mWVSpeedCheck.setWebChromeClient(object : WebChromeClient() {
            //구현 하지 않으면 alert 반응 안함!! consol.log 생활화!! ^_^
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                Log.e(javaClass.name + "jky", "onJsAlert() url:$url, message:$message")
                //return super.onJsAlert(view, url, message, result);
                AlertDialog.Builder(view.context)
                    .setTitle("")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                        object : DialogInterface.OnClickListener {
                            override fun onClick(dialog: DialogInterface?, which: Int) {
                                result.confirm()
                            }
                        })
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                Log.e(javaClass.name + "jky", "onJsConfirm() url:$url, message$message")
                //return super.onJsConfirm(view, url, message, result);
                AlertDialog.Builder(view.context)
                    .setTitle("")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                        object : DialogInterface.OnClickListener {
                            override fun onClick(dialog: DialogInterface?, which: Int) {
                                result.confirm()
                            }
                        })
                    .setNegativeButton(android.R.string.cancel,
                        object : DialogInterface.OnClickListener {
                            override fun onClick(dialog: DialogInterface?, which: Int) {
                                result.cancel()
                            }
                        })
                    .create()
                    .show()
                return true
            }
        })

        mWVSpeedCheck.loadUrl("file:///android_asset/www/index.html")


    }





}
