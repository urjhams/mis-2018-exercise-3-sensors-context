package com.example.mis.sensor;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.WindowManager;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    //example variables
    private double[] rndAccExamplevalues;
    private double[] freqCounts;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private LineGraphSeries<DataPoint> seriesX;
    private LineGraphSeries<DataPoint> seriesY;
    private LineGraphSeries<DataPoint> seriesZ;
    private LineGraphSeries<DataPoint> seriesM;

    private static double currentX = 0;
    private LinkedBlockingDeque<Double> xQueue = new LinkedBlockingDeque<>(10);
    private LinkedBlockingDeque<Double> yQueue = new LinkedBlockingDeque<>(10);
    private LinkedBlockingDeque<Double> zQueue = new LinkedBlockingDeque<>(10);
    private LinkedBlockingDeque<Double> mQueue = new LinkedBlockingDeque<>(10);

    double currentAcceleration = 0;

    public MainActivity() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Objects.requireNonNull(getSupportActionBar()).hide();
        }
        setContentView(R.layout.activity_main);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prepareSensor();
        prepareGraph();

        //initiate and fill example array with random values
        rndAccExamplevalues = new double[64];
        randomFill(rndAccExamplevalues);
        new FFTAsynctask(64).execute(rndAccExamplevalues);
    }

    private void prepareSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager != null ?
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) :
                null;
    }

    private void prepareGraph() {
        //--------------- graph init

        GraphView graphX = findViewById(R.id.graphX);
        GraphView graphY = findViewById(R.id.graphY);
        GraphView graphZ = findViewById(R.id.graphZ);
        GraphView graphM = findViewById(R.id.graphM);

        seriesX = new LineGraphSeries<>();
        initSeries(seriesX,Color.RED);
        seriesY = new LineGraphSeries<>();
        initSeries(seriesY,Color.GREEN);
        seriesZ = new LineGraphSeries<>();
        initSeries(seriesZ,Color.CYAN);
        seriesM = new LineGraphSeries<>();
        initSeries(seriesM,Color.MAGENTA);

        setStyleOf(graphX,"X axis",-20,20,seriesX);
        setStyleOf(graphY,"Y axis",-20,20,seriesY);
        setStyleOf(graphZ,"Z axis",-20,20,seriesZ);
        setStyleOf(graphM,"Magnitude",0,40,seriesM);


        //-------------- start chart thread

        ThreadPoolExecutor liveChartExecutor =
                (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        if (liveChartExecutor != null) {
            liveChartExecutor.execute(
                    new AccelerationChart(
                            new AccelerationChartHandler()));
        }
    }

    private void initSeries(LineGraphSeries<DataPoint> series, int color) {
        series.setColor(color);
        series.setDataPointsRadius(10);
    }

    private void setStyleOf(GraphView graph,
                            String name,
                            double minY,
                            double maxY,
                            LineGraphSeries<DataPoint> source) {
        graph.addSeries(source);
        graph.setTitle(name);
        graph.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.HORIZONTAL);
        graph.getGridLabelRenderer().setHorizontalLabelsColor(Color.WHITE);

        Viewport viewPort = graph.getViewport();
        viewPort.setScrollable(false);
        viewPort.setScalable(false);

        viewPort.setXAxisBoundsManual(true);
        viewPort.setMinX(0);
        viewPort.setMaxX(500);


        viewPort.setYAxisBoundsManual(true);
        viewPort.setMinY(minY);
        viewPort.setMaxY(maxY);
    }

    private void getAccelerometer(SensorEvent event) {

        float[] values = event.values;

        double x = values[0];
        double y = values[1];
        double z = values[2];

        double accelerationToSqrt = (x * x + y * y + z * z) /
                (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH);

        currentAcceleration = Math.sqrt(accelerationToSqrt);

        double magnitude = Math.sqrt(x * x + y * y + z * z);

        xQueue.offer(x);
        yQueue.offer(y);
        zQueue.offer(z);
        mQueue.offer(magnitude);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            getAccelerometer(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }


    /**
     * Implements the fft functionality as an async task
     * FFT(int n): constructor with fft length
     * fft(double[] x, double[] y)
     */

    @SuppressLint("StaticFieldLeak")
    private class FFTAsynctask extends AsyncTask<double[], Void, double[]> {

        private int wsize; //window size must be power of 2

        // constructor to set window size
        FFTAsynctask(int wsize) {
            this.wsize = wsize;
        }

        @Override
        protected double[] doInBackground(double[]... values) {


            double[] realPart = values[0].clone(); // actual acceleration values
            double[] imagPart = new double[wsize]; // init empty

            /*
              Init the FFT class with given window size and run it with your input.
              The fft() function overrides the realPart and imagPart arrays!
             */
            FFT fft = new FFT(wsize);
            fft.fft(realPart, imagPart);
            //init new double array for magnitude (e.g. frequency count)
            double[] magnitude = new double[wsize];


            //fill array with magnitude values of the distribution
            for (int i = 0; wsize > i ; i++) {
                magnitude[i] = Math.sqrt(Math.pow(realPart[i], 2) + Math.pow(imagPart[i], 2));
            }

            return magnitude;

        }

        @Override
        protected void onPostExecute(double[] values) {
            //hand over values to global variable after background task is finished
            freqCounts = values;
        }
    }




    /**
     * little helper function to fill example with random double values
     */
    public void randomFill(double[] array){
        Random rand = new Random();
        for(int i = 0; array.length > i; i++){
            array[i] = rand.nextDouble();
        }
    }


    @SuppressLint("HandlerLeak")
    private class AccelerationChartHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Double accelerationY1 = 0.0D;
            Double accelerationY2 = 0.0D;
            Double accelerationY3 = 0.0D;
            Double accelerationY4 = 0.0D;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (!Objects.equals(msg.getData().getString("X_VALUE"), "null")) {
                    accelerationY1 =
                            (Double.parseDouble(msg.getData().getString("X_VALUE")));
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (!Objects.requireNonNull(msg.getData().getString("Y_VALUE")).equals("null")) {
                    accelerationY2 =
                            (Double.parseDouble(msg.getData().getString("Y_VALUE")));
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (!Objects.requireNonNull(msg.getData().getString("Z_VALUE")).equals("null")) {
                    accelerationY3 =
                            (Double.parseDouble(msg.getData().getString("Z_VALUE")));
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (!Objects.requireNonNull(msg.getData().getString("M_VALUE")).equals("null")) {
                    accelerationY4 =
                            (Double.parseDouble(msg.getData().getString("M_VALUE")));
                }
            }
            seriesX.appendData(new DataPoint(currentX, accelerationY1),
                    true,
                    500);
            seriesY.appendData(new DataPoint(currentX, accelerationY2),
                    true,
                    500);
            seriesZ.appendData(new DataPoint(currentX, accelerationY3),
                    true,
                    500);
            seriesM.appendData(new DataPoint(currentX, accelerationY4),
                    true,
                    500);
            currentX += 5;
        }
    }

    private class AccelerationChart implements Runnable {
        private boolean drawChart = true;
        private Handler handler;

        AccelerationChart(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void run() {
            while (drawChart) {
                Double accelerationY1;
                Double accelerationY2;
                Double accelerationY3;
                Double accelerationY4;
                try {
                    Thread.sleep(10);
                    accelerationY1 = xQueue.poll();
                    accelerationY2 = yQueue.poll();
                    accelerationY3 = zQueue.poll();
                    accelerationY4 = mQueue.poll();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    continue;
                }
                if (accelerationY1 == null ||
                        accelerationY2 == null ||
                        accelerationY3 == null ||
                        accelerationY4 == null) {
                    continue;
                }

                Message msgObj = handler.obtainMessage();
                Bundle bundle = new Bundle();
                bundle.putString("X_VALUE", String.valueOf(accelerationY1));
                bundle.putString("Y_VALUE", String.valueOf(accelerationY2));
                bundle.putString("Z_VALUE", String.valueOf(accelerationY3));
                bundle.putString("M_VALUE", String.valueOf(accelerationY4));
                msgObj.setData(bundle);
                handler.sendMessage(msgObj);
            }
        }
    }
}
