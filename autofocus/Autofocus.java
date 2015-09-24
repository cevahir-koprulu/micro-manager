/*
 * AUTHOR:
 * Pakpoom Subsoontorn & Hernan Garcia, June, 2007
 * Nenad Amodaj, nenad@amodaj.com
 *
 * Copyright (c)  California Institute of Technology
 * Copyright (c)  Regents of the University of California
 * Copyright (c)  100X Imaging Inc, www.100ximaging.com
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageWindow;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.Color;
import java.util.Arrays;
import java.util.Date;
import java.util.prefs.Preferences;

import mmcorej.CMMCore;
import mmcorej.StrVector;

import org.micromanager.api.ScriptInterface;
import org.micromanager.utils.AutofocusBase;
import org.micromanager.utils.MMException;
import org.micromanager.utils.PropertyItem;

/*
 * Created on June 2nd 2007
 * author: Pakpoom Subsoontorn & Hernan Garcia
 */

/**
 * ImageJ plugin wrapper for uManager.
 */

/* This plugin take a stack of snapshots and computes their sharpness

 */
public class Autofocus extends AutofocusBase implements org.micromanager.api.Autofocus  {

   private static final String KEY_SIZE_FIRST = "1st step size";
   private static final String KEY_NUM_FIRST = "1st step number";
   private static final String KEY_SIZE_SECOND = "2nd step size";
   private static final String KEY_NUM_SECOND = "2nd step number";
   private static final String KEY_THRES    = "Threshold";
   private static final String KEY_CROP_SIZE = "Crop ratio";
   private static final String KEY_CHANNEL = "Channel";
   private static final String NOCHANNEL = "";
   //private static final String AF_SETTINGS_NODE = "micro-manager/extensions/autofocus";
   
   private static final String AF_DEVICE_NAME = "JAF(H&P)";

   private ScriptInterface app_;
   private CMMCore core_;
   private ImageProcessor ipCurrent_ = null;

   public double SIZE_FIRST = 2;//
   public int NUM_FIRST = 1; // +/- #of snapshot
   public  double SIZE_SECOND = 0.2;
   public  int NUM_SECOND = 5;
   public double THRES = 0.02;
   public double CROP_SIZE = 0.2; 
   public String CHANNEL="";

   private double indx = 0; //snapshot show new window iff indx = 1 

   private boolean verbose_ = true; // displaying debug info or not

   private Preferences prefs_;//********

   private String channelGroup_;
   private double curDist;
   private double baseDist;
   private double bestDist;
   private double curSh;
   private double bestSh;
   private long t0;
   private long tPrev;
   private long tcur;

   public Autofocus(){ //constructor!!!
      super();
      //Preferences root = Preferences.userNodeForPackage(this.getClass());
      //prefs_ = root.node(root.absolutePath()+"/"+AF_SETTINGS_NODE);
      
      // set-up properties
      createProperty(KEY_SIZE_FIRST, Double.toString(SIZE_FIRST));
      createProperty(KEY_NUM_FIRST, Integer.toString(NUM_FIRST));
      createProperty(KEY_SIZE_SECOND, Double.toString(SIZE_SECOND));
      createProperty(KEY_NUM_SECOND, Integer.toString(NUM_SECOND));
      createProperty(KEY_THRES, Double.toString(THRES));
      createProperty(KEY_CROP_SIZE, Double.toString(CROP_SIZE));
      createProperty(KEY_CHANNEL, CHANNEL);
      
      loadSettings();
   }
   
   public void applySettings() {
      try {
         SIZE_FIRST = Double.parseDouble(getPropertyValue(KEY_SIZE_FIRST));
         NUM_FIRST = Integer.parseInt(getPropertyValue(KEY_NUM_FIRST));
         SIZE_SECOND = Double.parseDouble(getPropertyValue(KEY_SIZE_SECOND));
         NUM_SECOND = Integer.parseInt(getPropertyValue(KEY_NUM_SECOND));
         THRES = Double.parseDouble(getPropertyValue(KEY_THRES));
         CROP_SIZE = Double.parseDouble(getPropertyValue(KEY_CROP_SIZE));
         CHANNEL = getPropertyValue(KEY_CHANNEL);
      
      } catch (NumberFormatException e) {
         // TODO Auto-generated catch block
         e.printStackTrace();
      } catch (MMException e) {
         // TODO Auto-generated catch block
         e.printStackTrace();
      }
      
   }

   public void run(String arg) {
      t0 = System.currentTimeMillis();
      bestDist = 5000;
      bestSh = 0;
      //############# CHECK INPUT ARG AND CORE ########
      if (arg.compareTo("silent") == 0)
         verbose_ = false;
      else
         verbose_ = true;

      if (arg.compareTo("options") == 0){
         app_.getAutofocusManager().showOptionsDialog();
      }

      if (core_ == null) {
         // if core object is not set attempt to get its global handle
         core_ = app_.getMMCore();
      }

      if (core_ == null) {
         IJ.error("Unable to get Micro-Manager Core API handle.\n" +
         "If this module is used as ImageJ plugin, Micro-Manager Studio must be running first!");
         return;
      }
      
      applySettings();

      //######################## START THE ROUTINE ###########

      try{
         IJ.log("Autofocus started.");
         boolean shutterOpen = core_.getShutterOpen();
         core_.setShutterOpen(true);
         boolean autoShutter = core_.getAutoShutter();
         core_.setAutoShutter(false);


         //########System setup##########
         if (!CHANNEL.equals(NOCHANNEL))
            core_.setConfig(channelGroup_, CHANNEL);
         core_.waitForSystem();
         if (core_.getShutterDevice().trim().length() > 0)
         {
            core_.waitForDevice(core_.getShutterDevice());
         }
         //delay_time(3000);


         //Snapshot, zdistance and sharpNess before AF 
         /* curDist = core_.getPosition(core_.getFocusDevice());
         indx =1;
         snapSingleImage();
         indx =0;

         tPrev = System.currentTimeMillis();
         curSh = sharpNess(ipCurrent_);
         tcur = System.currentTimeMillis()-tPrev;*/



         //set z-distance to the lowest z-distance of the stack
         curDist = core_.getPosition(core_.getFocusDevice());
         baseDist = curDist-SIZE_FIRST*NUM_FIRST;
         core_.setPosition(core_.getFocusDevice(),baseDist);
         core_.waitForDevice(core_.getFocusDevice());
         delay_time(300);

         IJ.log(" Before rough search: " +String.valueOf(curDist));


         //Rough search
         for(int i = 0; i < 2*NUM_FIRST+1; i++ ){
            tPrev = System.currentTimeMillis();

            core_.setPosition(core_.getFocusDevice(),baseDist+i*SIZE_FIRST);
            core_.waitForDevice(core_.getFocusDevice());


            curDist = core_.getPosition(core_.getFocusDevice());
            // indx =1;
            snapSingleImage();
            // indx =0;    



            curSh = computeScore(ipCurrent_);

            if(curSh > bestSh){
               bestSh = curSh;
               bestDist = curDist;
            } 
            else if (bestSh - curSh > THRES*bestSh && bestDist < 5000) {
               break;
            }
            tcur = System.currentTimeMillis()-tPrev;

            //===IJ.log(String.valueOf(curDist)+" "+String.valueOf(curSh)+" " +String.valueOf(tcur));	
         }


         //===IJ.log("BEST_DIST_FIRST"+String.valueOf(bestDist)+" BEST_SH_FIRST"+String.valueOf(bestSh));

         baseDist = bestDist-SIZE_SECOND*NUM_SECOND;
         core_.setPosition(core_.getFocusDevice(),baseDist);
         delay_time(100);

         bestSh = 0;

         //Fine search
         for(int i = 0; i < 2*NUM_SECOND+1; i++ ){
            tPrev = System.currentTimeMillis();
            core_.setPosition(core_.getFocusDevice(),baseDist+i*SIZE_SECOND);
            core_.waitForDevice(core_.getFocusDevice());

            curDist = core_.getPosition(core_.getFocusDevice());
            // indx =1;
            snapSingleImage();
            // indx =0;    

            curSh = computeScore(ipCurrent_);

            if(curSh > bestSh){
               bestSh = curSh;
               bestDist = curDist;
            } 
            else if (bestSh - curSh > THRES*bestSh && bestDist < 5000){
               break;
            }
            tcur = System.currentTimeMillis()-tPrev;

            //===IJ.log(String.valueOf(curDist)+" "+String.valueOf(curSh)+" "+String.valueOf(tcur));
         }


         IJ.log("BEST_DIST_SECOND"+String.valueOf(bestDist)+" BEST_SH_SECOND"+String.valueOf(bestSh));

         core_.setPosition(core_.getFocusDevice(),bestDist);
         // indx =1;
         snapSingleImage();
         // indx =0;  
         core_.setShutterOpen(shutterOpen);
         core_.setAutoShutter(autoShutter);


         IJ.log("Total Time: "+ String.valueOf(System.currentTimeMillis()-t0));
      }
      catch(Exception e)
      {
         IJ.error(e.getMessage());
      }     
   }

   //take a snapshot and save pixel values in ipCurrent_
   private boolean snapSingleImage() {

      try {
         core_.snapImage();
         Object img = core_.getImage();
         ImagePlus implus = newWindow();// this step will create a new window iff indx = 1
         implus.getProcessor().setPixels(img);
         ipCurrent_ = implus.getProcessor();
      } catch (Exception e) {
         IJ.log(e.getMessage());
         IJ.error(e.getMessage());
         return false;
      }

      return true;
   }

   //waiting    
   private void delay_time(double delay){
      Date date = new Date();
      long sec = date.getTime();
      while(date.getTime()<sec+delay){
         date = new Date();
      }
   }

   /*calculate the sharpness of a given image (in "impro").*/
   private double sharpNessp(ImageProcessor impro){


      int width =  (int)(CROP_SIZE*core_.getImageWidth());
      int height = (int)(CROP_SIZE*core_.getImageHeight());
      int ow = (int)(((1-CROP_SIZE)/2)*core_.getImageWidth());
      int oh = (int)(((1-CROP_SIZE)/2)*core_.getImageHeight());

      double[][] medPix = new double[width][height];
      double sharpNess = 0;
      double[] windo = new double[9];

      /*Apply 3x3 median filter to reduce noise*/
      for (int i=0; i<width; i++){
         for (int j=0; j<height; j++){

            windo[0] = (double)impro.getPixel(ow+i-1,oh+j-1);
            windo[1] = (double)impro.getPixel(ow+i,oh+j-1);
            windo[2] = (double)impro.getPixel(ow+i+1,oh+j-1);
            windo[3] = (double)impro.getPixel(ow+i-1,oh+j);
            windo[4] = (double)impro.getPixel(ow+i,oh+j);
            windo[5] = (double)impro.getPixel(ow+i+1,oh+j);
            windo[6] = (double)impro.getPixel(ow+i-1,oh+j+1);
            windo[7] = (double)impro.getPixel(ow+i,oh+j+1);
            windo[8] = (double)impro.getPixel(ow+i+1,oh+j+1);

            medPix[i][j] = median(windo);
         } 
      }

      /*Edge detection using a 3x3 filter: [-2 -1 0; -1 0 1; 0 1 2]. Then sum all pixel values. Ideally, the sum is large if most edges are sharp*/

      for (int k=1; k<width-1; k++){
         for (int l=1; l<height-1; l++){

            sharpNess = sharpNess + Math.pow((-2*medPix[k-1][l-1]- medPix[k][l-1]-medPix[k-1][l]+medPix[k+1][l]+medPix[k][l+1]+2*medPix[k+1][l+1]),2);

         } 
      }
      return sharpNess;
   }


   /**
    * calculate the sharpness of a given image (in "impro").
    * @param impro ImageJ Processor
    * @return sharpness score
    */
   @Override
   public double computeScore(final ImageProcessor impro){


      int width =  (int)(CROP_SIZE*core_.getImageWidth());
      int height = (int)(CROP_SIZE*core_.getImageHeight());
      int ow = (int)(((1-CROP_SIZE)/2)*core_.getImageWidth());
      int oh = (int)(((1-CROP_SIZE)/2)*core_.getImageHeight());

      // double[][] medPix = new double[width][height];
      double sharpNess = 0;
      //double[] windo = new double[9];

      /*Apply 3x3 median filter to reduce noise*/

      /*   for (int i=0; i<width; i++){
         for (int j=0; j<height; j++){

	    windo[0] = (double)impro.getPixel(ow+i-1,oh+j-1);
	    windo[1] = (double)impro.getPixel(ow+i,oh+j-1);
            windo[2] = (double)impro.getPixel(ow+i+1,oh+j-1);
            windo[3] = (double)impro.getPixel(ow+i-1,oh+j);
            windo[4] = (double)impro.getPixel(ow+i,oh+j);
            windo[5] = (double)impro.getPixel(ow+i+1,oh+j);
            windo[6] = (double)impro.getPixel(ow+i-1,oh+j+1);
            windo[7] = (double)impro.getPixel(ow+i,oh+j+1);
            windo[8] = (double)impro.getPixel(ow+i+1,oh+j+1);

            medPix[i][j] = median(windo);
         } 
	 }*/        

      //tPrev = System.currentTimeMillis();     

      impro.medianFilter();
      int[] ken = {2, 1, 0, 1, 0, -1, 0, -1, -2};
      impro.convolve3x3(ken);
      for (int i=0; i<width; i++){
         for (int j=0; j<height; j++){

            sharpNess = sharpNess + Math.pow(impro.getPixel(ow+i,oh+j),2);
         } 
      }

      // tcur = System.currentTimeMillis()-tPrev;

      /*Edge detection using a 3x3 filter: [-2 -1 0; -1 0 1; 0 1 2]. Then sum all pixel values. Ideally, the sum is large if most edges are sharp*/

      /*  for (int k=1; k<width-1; k++){
         for (int l=1; l<height-1; l++){

	     sharpNess = sharpNess + Math.pow((-2*medPix[k-1][l-1]- medPix[k][l-1]-medPix[k-1][l]+medPix[k+1][l]+medPix[k][l+1]+2*medPix[k+1][l+1]),2);

         } 
	 }*/

      return sharpNess;
   }


   //making a new window for a new snapshot.
   private ImagePlus newWindow(){
      ImagePlus implus;
      ImageProcessor ip;
      long byteDepth = core_.getBytesPerPixel();

      if (byteDepth == 1){
         ip = new ByteProcessor((int)core_.getImageWidth(),(int)core_.getImageHeight());
      } else  {
         ip = new ShortProcessor((int)core_.getImageWidth(), (int)core_.getImageHeight());
      }
      ip.setColor(Color.black);
      ip.fill();

      implus = new ImagePlus(String.valueOf(curDist), ip);
      if(indx == 1){
         if (verbose_) {
            // create image window if we are in the verbose mode
            ImageWindow imageWin = new ImageWindow(implus);
         }
      }
      return implus;
   }

   private double median(double[] arr){ 
      double [] newArray = Arrays.copyOf(arr, arr.length);
      Arrays.sort(newArray);
      int middle = newArray.length/2;
      return (newArray.length%2 == 1) ? newArray[middle] : (newArray[middle-1] + newArray[middle]) / 2.0;
   }

   public double fullFocus() {
      run("silent");
      return 0;
   }

   public String getVerboseStatus() {
      return new String("OK");
   }

   public double incrementalFocus() {
      run("silent");
      return 0;
   }

   public void focus(double coarseStep, int numCoarse, double fineStep, int numFine) {
      SIZE_FIRST = coarseStep;
      NUM_FIRST = numCoarse;
      SIZE_SECOND = fineStep;
      NUM_SECOND = numFine;

      run("silent");
   }

   public PropertyItem[] getProperties() {
      // use default dialog
      // make sure we have the right list of channels
            
      channelGroup_ = core_.getChannelGroup();
      StrVector channels = core_.getAvailableConfigs(channelGroup_);
      String allowedChannels[] = new String[(int)channels.size() + 1];
      allowedChannels[0] = NOCHANNEL;

      try {
         PropertyItem p = getProperty(KEY_CHANNEL);
         boolean found = false;
         for (int i=0; i<channels.size(); i++) {
            allowedChannels[i+1] = channels.get(i);
            if (p.value.equals(channels.get(i)))
               found = true;
         }
         p.allowed = allowedChannels;
         if (!found)
            p.value = allowedChannels[0];
         setProperty(p);
      } catch (MMException e1) {
         // TODO Auto-generated catch block
         e1.printStackTrace();
      }

      return super.getProperties();
   }
      
   void setCropSize(double cs) {
      CROP_SIZE = cs;
   }
   
   void setThreshold(double thr) {
      THRES = thr;
   }

   public double getCurrentFocusScore() {
      // TODO Auto-generated method stub
      return 0;
   }

   public int getNumberOfImages() {
      // TODO Auto-generated method stub
      return 0;
   }

   public String getDeviceName() {
      return AF_DEVICE_NAME;
   }

   public void setApp(ScriptInterface app) {
      app_ = app;
      core_ = app.getMMCore();
   }
}   
