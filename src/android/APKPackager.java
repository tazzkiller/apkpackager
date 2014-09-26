// Copyright (c) 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.channels.*;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.BufferedInputStream;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import kellinwood.security.zipsigner.ZipSigner;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import com.android.sdklib.build.*;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONObject;

import com.axml.enddec.BinaryXMLParser;
import com.axml.enddec.BinaryResourceParser;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaResourceApi;
import org.apache.cordova.CordovaWebView;
import org.json.JSONException;


public class APKPackager  extends CordovaPlugin {

    private String LOG_TAG = "APKPackage";
    private String returnMsg = "";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }
  
    @Override
    public void initialize(final CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
    }

    @Override
    public boolean execute(String action, final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        if ("packageAPK".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    pack(args, callbackContext);
                }
            });
            return true;
        } 
        return false;
    }

    private void pack(CordovaArgs args, CallbackContext callbackContext) {
	File playground = null;
	File template = null;
        Uri srcTemplateUri = null;
	File wwwDir = null;
        File output = null;
        URL keyStoreUrl = null;
        char[] keyStorePassword = null;
        String keyAlias = null;
        char[] keyPassword = null;
        String appName = "";
        String packageName = "";
        String versionName = "";
        long versionCode = 0; // This is actually an unsigned int32.

        CordovaResourceApi cra = webView.getResourceApi();
	try {
          playground = cra.mapUriToFile(cra.remapUri(Uri.parse(args.getString(0))));
          srcTemplateUri = cra.remapUri(Uri.parse(args.getString(1)));
        wwwDir = cra.mapUriToFile(cra.remapUri(Uri.parse(args.getString(2))));
          output = cra.mapUriToFile(cra.remapUri(Uri.parse(args.getString(3))));
        keyStoreUrl = new URL(cra.remapUri(Uri.parse(args.getString(4))).toString());
        keyStorePassword = args.getString(5).toCharArray();
        keyAlias = args.getString(6);
        keyPassword = args.getString(7).toCharArray();
        JSONObject jObject = args.getJSONObject(8);
        appName = jObject.getString("appName");
        packageName = jObject.getString("packageName");
        versionName = jObject.getString("versionName");
        versionCode = jObject.getLong("versionCode");

        template = new File(playground, "template");
	} catch (Exception e) {
        e.printStackTrace();
            callbackContext.error("Missing arguments: "+e.getMessage());
            return;
	}

        try {
            deleteDir(playground);
            output.delete();
            initTemplate(cra, cordova.getActivity().getAssets(), srcTemplateUri, template);
        } catch (IOException e) {
            e.printStackTrace();
            callbackContext.error(e.getMessage());
            return;
        }


        try {
	  BinaryXMLParser parser = new BinaryXMLParser(template.getAbsolutePath()+"/AndroidManifest.xml");
  	  parser.parseXML();
	  parser.changeString(parser.getAppName(), appName);
	  parser.changeString(parser.getPackageName(), packageName);
	  parser.changeString(parser.getActivityName(), appName);
	  parser.changeString(parser.getVersion(), versionName);
        // TODO: set versionCode here.
  	  parser.exportXML(template.getAbsolutePath()+"/AndroidManifest.xml");

	  BinaryResourceParser resParser = new BinaryResourceParser(template.getAbsolutePath()+"/resources.arsc");
	  resParser.parseResource();
	  resParser.changePackageName(packageName);
	  //resParser.changePackageName("org.chromium.cadt.template");
	  resParser.exportResource(template.getAbsolutePath()+"/resources.arsc");

	} catch (Exception e) {
	    callbackContext.error("Error at modifing the Android Manifest: "+e.getMessage());
	}

        String generatedApkPath = playground.getAbsolutePath()+"/temp-unsigned.apk";


	//TODO: to copy the assets directory in the template
	try {
	    File destWww = new File(new File(template, "assets"), "www");
	    mergeDirectory(cra, wwwDir, destWww);
	} catch (Exception e) {
            callbackContext.error("Error at assets copy: "+e.getMessage());
            return;
	}

	File fakeResZip;
        // take the completed package and make the unsigned APK
        try{
            // ApkBuilder REALLY wants a resource zip file in the contructor
            // but the composite res is not a zip - so hand it a dummy
            fakeResZip = new File(playground,"FakeResourceZipFile.zip");
            writeZipfile(fakeResZip);

            ApkBuilder b = new ApkBuilder(generatedApkPath,fakeResZip.getPath(), null,null,null,null);
	    b.addSourceFolder(template);
            b.sealApk();
        } catch (Exception e) {
            callbackContext.error("ApkBuilder Error: "+e.getMessage());
            return;
        }

        // sign the APK with the supplied key/cert
        try {
            ZipSigner zipSigner = new ZipSigner();
            zipSigner.signZip(keyStoreUrl, null, keyStorePassword, keyAlias, keyPassword, "RSA", generatedApkPath, output.getAbsolutePath());
        } catch (Exception e) {
            callbackContext.error("ZipSigner Error: "+e.getMessage());
            return;
	    }

        // After signing apk , delete intermediate stuff
        try {
            new File(generatedApkPath).delete();
	    fakeResZip.delete();
        } catch (Exception e) {
            callbackContext.error("Error cleaning up: "+e.getMessage());
            return;
	}

        callbackContext.success("succes for " + appName);
    }

    private static String join(String a, String b) {
        if (a.endsWith("/")) {
            a = a.substring(0, a.length() - 1);
        }
        if (b.startsWith("/")) {
            b = b.substring(1);
        }
        return a + '/' + b;
    }

    private static void mergeFromAssets(CordovaResourceApi cra, AssetManager assetManager, String from, Uri to) throws IOException {
        String[] assets = assetManager.list(from);
        if (assets.length == 0) {
            cra.copyResource(Uri.parse(join("file:///android_asset/", from)), to);
        } else {
            File f = new File(to.getPath());
            f.mkdirs();
            for (String a : assets) {
                mergeFromAssets(cra, assetManager, join(from, a), Uri.parse(join(to.toString(), a)));
            }
        }
    }

    private static void initTemplate(CordovaResourceApi cra, AssetManager assetManager, Uri srcTemplateUri, File dstTemplate) throws IOException {
        File f = cra.mapUriToFile(srcTemplateUri);
        if (f != null) {
            mergeDirectory(cra, f, dstTemplate);
        } else if (srcTemplateUri.toString().startsWith("file:///android_asset/")) {
            String assetPath = srcTemplateUri.getPath().substring("/android_asset/".length());
            mergeFromAssets(cra, assetManager, assetPath, Uri.fromFile(dstTemplate));
        } else {
            throw new UnsupportedOperationException("Cannot handle URL: " + srcTemplateUri);
        }
    }

    private void deleteDir(File dir){
        if(!dir.exists()) return;
        if(dir.isDirectory()) {
            File [] files = dir.listFiles();
            if(files != null) {
                for( File f : files ) {
    	            if(f.isDirectory()) deleteDir(f);
    	            else f.delete();
                }
            }
        }
        dir.delete();
    }
    private void writeZipfile(File zipFile) throws IOException {
        if(zipFile.exists()) zipFile.delete();
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile));
        ZipEntry e = new ZipEntry("dummydir");
        out.putNextEntry(e);
        out.closeEntry();
        out.close();
    }

    private void writeStringToFile(String str, File target) {
    	FileWriter fw=null;
    	try {
    		File dir = target.getParentFile();
    		if(!dir.exists()) dir.mkdirs();
			fw = new FileWriter(target);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try{
			fw.close();
			} catch(Exception e) {}
		}
    	
    }
    
    /* overwrite stuff from a default zip with the things in sourcedir 
    */
    private static void mergeDirectory(CordovaResourceApi cra, File srcdir, File workdir)
            throws FileNotFoundException, IOException {
        File[] files = srcdir.listFiles();
        for(File file : files){
            if(file.isDirectory()) {
                File targetDir = new File(workdir, file.getName());
                targetDir.mkdirs();
                mergeDirectory(cra, file, targetDir);
            } else {
                File targetFile = new File(workdir, file.getName());
                if(targetFile.exists()) targetFile.delete();
                cra.copyResource(Uri.fromFile(file), Uri.fromFile(targetFile));
            }
        }
    }
}
