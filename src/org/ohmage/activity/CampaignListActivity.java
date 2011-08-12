/*******************************************************************************
 * Copyright 2011 The Regents of the University of California
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.ohmage.activity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jbcrypt.BCrypt;

import org.achartengine.ChartFactory;
import org.achartengine.chart.PointStyle;
import org.achartengine.chartdemo.demo.chart.AverageTemperatureChart;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ohmage.OhmageApi;
import org.ohmage.OhmageApplication;
import org.ohmage.CampaignManager;
import org.ohmage.SharedPreferencesHelper;
import org.ohmage.Utilities;
import org.ohmage.OhmageApi.CampaignReadResponse;
import org.ohmage.OhmageApi.Result;
import org.ohmage.db.Campaign;
import org.ohmage.db.DbHelper;
import org.ohmage.feedback.FBTestActivity;
import org.ohmage.feedback.FeedbackService;

import com.commonsware.cwac.wakeful.WakefulIntentService;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint.Align;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.ohmage.R;
import edu.ucla.cens.mobility.glue.MobilityInterface;

public class CampaignListActivity extends ListActivity {

	private static final String TAG = "CampaignListActivity";

	private static final int DIALOG_DOWNLOAD_PROGRESS = 0;
	private static final int DIALOG_INTERNAL_ERROR = 1;
	private static final int DIALOG_NETWORK_ERROR = 2;
	private static final int DIALOG_USER_DISABLED = 3;
	private static final int DIALOG_AUTH_ERROR = 4;
	
	private CampaignListUpdateTask mTask;
	private CampaignDownloadTask mTask2;
//	private boolean mShowingProgressDialog = false;
	//private List<HashMap<String, String>> mData;
	private List<Campaign> mLocalCampaigns;
	private List<Campaign> mRemoteCampaigns;
	private LayoutInflater mInflater;
	private View mFooter;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		Log.i(TAG, "onCreate()");
		
//		setContentView(R.layout.campaign_list);
		
		final SharedPreferencesHelper preferencesHelper = new SharedPreferencesHelper(this);
		
		if (preferencesHelper.isUserDisabled()) {
        	((OhmageApplication) getApplication()).resetAll();
        }
		
		if (!preferencesHelper.isAuthenticated()) {
			Log.i(TAG, "no credentials saved, so launch Login");
			startActivity(new Intent(this, LoginActivity.class));
			finish();
		} else if (SharedPreferencesHelper.IS_SINGLE_CAMPAIGN) {
			Log.e(TAG, "Attempting to start CampaignListActivity when in SINGLE_CAMPAIGN mode. Aborting!");
			Toast.makeText(this, "Attempting to start CampaignListActivity when in SINGLE_CAMPAIGN mode. Aborting!", Toast.LENGTH_LONG).show();
			finish();
		} else {
			
			String [] from = new String [] {"name", "urn"};
	        int [] to = new int [] {android.R.id.text1, android.R.id.text2};
	        
	        //mData = new ArrayList<HashMap<String,String>>();
	        
	        mLocalCampaigns = new ArrayList<Campaign>();
	        mRemoteCampaigns = new ArrayList<Campaign>();
	        
	        mInflater = (LayoutInflater)getSystemService(LAYOUT_INFLATER_SERVICE);
	        mFooter = mInflater.inflate(R.layout.campaign_list_footer, null);
	        mFooter.setVisibility(View.GONE);
	        getListView().addFooterView(mFooter);
	        
	        getListView().setOnItemLongClickListener(mItemLongClickListener);
			
			setListAdapter(new CampaignListAdapter(this, mLocalCampaigns, mRemoteCampaigns, R.layout.list_item_two_line_with_image, R.layout.list_header));
			//setListAdapter(new SimpleAdapter(this, mData , android.R.layout.simple_list_item_2, from, to));
			
			Object retained = getLastNonConfigurationInstance();
	        
	        if (retained instanceof CampaignListUpdateTask) {
	        	Log.i(TAG, "creating after configuration changed, restored CampaignListUpdateTask instance");
	        	mTask = (CampaignListUpdateTask) retained;
	        	mTask.setActivity(this);
	        } else if (retained instanceof CampaignDownloadTask) {
	        	Log.i(TAG, "creating after configuration changed, restored CampaignDownloadTask instance");
	        	mTask2 = (CampaignDownloadTask) retained;
	        	mTask2.setActivity(this);
	        } /*else {
	        	Log.i(TAG, "no tasks in progress");
	        	
	        	updateCampaignList();
	        }*/
		}
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		refreshCampaigns();
	}

	private void refreshCampaigns() {
		loadCampaigns();
		if (mTask == null) {
			updateCampaignList();
		}
	}

	@Override
	public Object onRetainNonConfigurationInstance() {
		Log.i(TAG, "configuration change");
		if (mTask != null) {
			Log.i(TAG, "retaining AsyncTask instance");
			mTask.setActivity(null);
			return mTask;
		} else if (mTask2 != null) {
			Log.i(TAG, "retaining AsyncTask instance");
			mTask2.setActivity(null);
			return mTask2;
		}
		return null;
	}
	
	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		super.onListItemClick(l, v, position, id);
		
		if (((CampaignListAdapter)getListAdapter()).getItemGroup(position) == CampaignListAdapter.GROUP_AVAILABLE) {
			//launch surveylistactivity
			
			Intent intent = new Intent(this, SurveyListActivity.class);
			intent.putExtra("campaign_urn", ((Campaign) getListView().getItemAtPosition(position)).mUrn);
			intent.putExtra("campaign_name", ((Campaign) getListView().getItemAtPosition(position)).mName);
			startActivity(intent);
		} else if (((CampaignListAdapter)getListAdapter()).getItemGroup(position) == CampaignListAdapter.GROUP_UNAVAILABLE) {
			//download campaign
			try {
				String campaignUrn = ((Campaign) getListView().getItemAtPosition(position)).mUrn;
				mTask2 = new CampaignDownloadTask(CampaignListActivity.this);
				SharedPreferencesHelper prefs = new SharedPreferencesHelper(this);
				mTask2.execute(prefs.getUsername(), prefs.getHashedPassword(), campaignUrn);
			} catch (Exception e) {
				Log.e(TAG, "Should be NullPointer exception occuring because of delayed update of list view after rotation", e);
			}
		}
	}
	
	OnItemLongClickListener mItemLongClickListener = new OnItemLongClickListener() {

		@Override
		public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
			if (((CampaignListAdapter)getListAdapter()).getItemGroup(position) == CampaignListAdapter.GROUP_AVAILABLE) {
				AlertDialog.Builder builder = new AlertDialog.Builder(CampaignListActivity.this)
				.setNegativeButton("No", null)
				.setTitle("Confirm")
				.setMessage("Are you sure you wish to remove this campaign from your campaigns list? All data associated with this campaign will be deleted.")
				.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						removeCampaign(((Campaign) getListView().getItemAtPosition(position)).mUrn);
						loadCampaigns();
						updateCampaignList();
					}

				});
				builder.show();
				return true;
			} else {
				return false;
			}
			
		}
	};
	
	private void removeCampaign(String urn) {
		CampaignManager.removeCampaign(this, urn);
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
	    MenuInflater inflater = getMenuInflater();
	    inflater.inflate(R.menu.campaign_list_menu, menu);
	  	return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Intent intent = null;
		switch (item.getItemId()) {
		case R.id.refresh_campaigns:
			refreshCampaigns();
			return true;
			
		case R.id.mobility_settings:
			MobilityInterface.showMobilityOptions(this);
			//Toast.makeText(this, "Mobility is not available.", Toast.LENGTH_SHORT).show();
			return true;
			
		case R.id.status:
			//WakefulIntentService.sendWakefulWork(this, UploadService.class);
			intent = new Intent(this, StatusActivity.class);
			startActivityForResult(intent, 1);
			return true;
			
		// FAISAL: testing, remove this later
		case R.id.fbtest:
			// start the feedback test activity
			Intent i = new Intent(this, FBTestActivity.class);
			startActivity(i);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		
		if (requestCode == 1) {
			if (resultCode == 123) {
				finish();
			} else if (resultCode == 125) {
				finish();
				startActivity(new Intent(CampaignListActivity.this, LoginActivity.class));
			}
		}
	}

	private void loadCampaigns() {
		
		mLocalCampaigns.clear();
		
		DbHelper dbHelper = new DbHelper(this);
        List<Campaign> campaigns = dbHelper.getCampaigns();
        
        for (Campaign c : campaigns) {
        	mLocalCampaigns.add(c);
        }
        
        ((CampaignListAdapter) getListAdapter()).notifyDataSetChanged();
	}
	
	private void updateCampaignList() {
		
		mTask = new CampaignListUpdateTask(CampaignListActivity.this);
		SharedPreferencesHelper prefs = new SharedPreferencesHelper(this);
		mTask.execute(prefs.getUsername(), prefs.getHashedPassword());
	}
	
	@Override
	protected Dialog onCreateDialog(int id) {
		Dialog dialog = super.onCreateDialog(id);
		AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
		switch (id) {
		case DIALOG_AUTH_ERROR:
        	dialogBuilder.setTitle("Error")
        				.setMessage("Unable to authenticate. Please check username and update the password.")
        				.setCancelable(true)
        				.setPositiveButton("OK", null)
        				/*.setNeutralButton("Help", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								startActivity(new Intent(LoginActivity.this, HelpActivity.class));
								//put extras for specific help on login error
							}
						})*/;
        	//add button for contact
        	dialog = dialogBuilder.create();        	
        	break;
        	
		case DIALOG_USER_DISABLED:
        	dialogBuilder.setTitle("Error")
        				.setMessage("This user account has been disabled.")
        				.setCancelable(true)
        				.setPositiveButton("OK", null)
        				/*.setNeutralButton("Help", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								startActivity(new Intent(LoginActivity.this, HelpActivity.class));
								//put extras for specific help on login error
							}
						})*/;
        	//add button for contact
        	dialog = dialogBuilder.create();        	
        	break;
        	
		case DIALOG_NETWORK_ERROR:
        	dialogBuilder.setTitle("Error")
        				.setMessage("Unable to communicate with server. Please try again later.")
        				.setCancelable(true)
        				.setPositiveButton("OK", null)
        				/*.setNeutralButton("Help", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								startActivity(new Intent(LoginActivity.this, HelpActivity.class));
								//put extras for specific help on http error
							}
						})*/;
        	//add button for contact
        	dialog = dialogBuilder.create();
        	break;
        
		case DIALOG_INTERNAL_ERROR:
        	dialogBuilder.setTitle("Error")
        				.setMessage("The server returned an unexpected response. Please try again later.")
        				.setCancelable(true)
        				.setPositiveButton("OK", null)
        				/*.setNeutralButton("Help", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								startActivity(new Intent(LoginActivity.this, HelpActivity.class));
								//put extras for specific help on http error
							}
						})*/;
        	//add button for contact
        	dialog = dialogBuilder.create();
        	break;
        	
		case DIALOG_DOWNLOAD_PROGRESS:
			ProgressDialog pDialog = new ProgressDialog(this);
			pDialog.setMessage("Downloading campaign configuration...");
			pDialog.setCancelable(false);
			//pDialog.setIndeterminate(true);
			dialog = pDialog;
        	break;
		}
		
		return dialog;
	}
	
	private void onCampaignListUpdated(CampaignReadResponse response) {
		
		mTask = null;
		
		
		if (response.getResult() == Result.SUCCESS) {
			//mData.clear();
			mRemoteCampaigns.clear();
			
			mFooter.setVisibility(View.GONE);
			
			// parse response
			try {
				JSONArray jsonItems = response.getMetadata().getJSONArray("items");
				for(int i = 0; i < jsonItems.length(); i++) {
					Campaign c = new Campaign();
					JSONObject data = response.getData();
					c.mUrn = jsonItems.getString(i); 
					c.mName = data.getJSONObject(c.mUrn).getString("name");
					c.mCreationTimestamp = data.getJSONObject(c.mUrn).getString("creation_timestamp");
					
					if (data.getJSONObject(c.mUrn).getString("running_state").equalsIgnoreCase("running")) {
						mRemoteCampaigns.add(c);
					} else {
						for (Campaign localCampaign : mLocalCampaigns) {
							if (c.mUrn.equals(localCampaign.mUrn)) {
								removeCampaign(c.mUrn);
							}
						}
					}
					
//					boolean isAlreadyAvailable = false;
//					for (Campaign availableCampaign : mLocalCampaigns) {
//						if (c.mUrn.equals(availableCampaign.mUrn)) {
//							isAlreadyAvailable = true;
//						}
//					}
//					
//					if (!isAlreadyAvailable) {
//						mRemoteCampaigns.add(c);
//					}
				}
			} catch (JSONException e) {
				Log.e(TAG, "Error parsing response json", e);
			}
			
			for (Campaign localCampaign : mLocalCampaigns) {
				
				int remoteIndex = -1;
				
				for (int i = 0; i < mRemoteCampaigns.size(); i++) {
					if (mRemoteCampaigns.get(i).mUrn.equals(localCampaign.mUrn)) {
						remoteIndex = i;
						break;
					}
				}
				
				if (remoteIndex != -1) {
					mRemoteCampaigns.remove(remoteIndex);
				} else {
					removeCampaign(localCampaign.mUrn);
				}
			}
			

		} else if (response.getResult() == Result.FAILURE) {
			Log.e(TAG, "Read failed due to error codes: " + Utilities.stringArrayToString(response.getErrorCodes(), ", "));
			
			boolean isAuthenticationError = false;
			boolean isUserDisabled = false;
			
			for (String code : response.getErrorCodes()) {
				if (code.charAt(1) == '2') {
					isAuthenticationError = true;
					
					if (code.equals("0201")) {
						isUserDisabled = true;
					}
				}
			}
			
			if (isUserDisabled) {
				new SharedPreferencesHelper(this).setUserDisabled(true);
				mFooter.setVisibility(View.VISIBLE);
				mFooter.findViewById(R.id.progress_bar).setVisibility(View.GONE);
				mFooter.findViewById(R.id.error_text).setVisibility(View.VISIBLE);
				((TextView)mFooter.findViewById(R.id.error_text)).setText("This user account has been disabled.");
			} else if (isAuthenticationError) {
				mFooter.setVisibility(View.VISIBLE);
				mFooter.findViewById(R.id.progress_bar).setVisibility(View.GONE);
				mFooter.findViewById(R.id.error_text).setVisibility(View.VISIBLE);
				((TextView)mFooter.findViewById(R.id.error_text)).setText("Unable to authenticate. Please check username and update the password.");
			} else {
				mFooter.setVisibility(View.VISIBLE);
				mFooter.findViewById(R.id.progress_bar).setVisibility(View.GONE);
				mFooter.findViewById(R.id.error_text).setVisibility(View.VISIBLE);
				((TextView)mFooter.findViewById(R.id.error_text)).setText("Internal error.");
			}
			
		} else if (response.getResult() == Result.HTTP_ERROR) {
			Log.e(TAG, "http error");
			
			mFooter.setVisibility(View.VISIBLE);
			mFooter.findViewById(R.id.progress_bar).setVisibility(View.GONE);
			mFooter.findViewById(R.id.error_text).setVisibility(View.VISIBLE);
			((TextView)mFooter.findViewById(R.id.error_text)).setText("Unable to communicate with server at this time.");
		} else {
			Log.e(TAG, "internal error");
			
			mFooter.setVisibility(View.VISIBLE);
			mFooter.findViewById(R.id.progress_bar).setVisibility(View.GONE);
			mFooter.findViewById(R.id.error_text).setVisibility(View.VISIBLE);
			((TextView)mFooter.findViewById(R.id.error_text)).setText("Internal server communication error.");
		} 
		
		loadCampaigns();
		
		// update listview
//		((SimpleAdapter) getListAdapter()).notifyDataSetChanged();
		((CampaignListAdapter) getListAdapter()).notifyDataSetChanged();
		
		try {
			dismissDialog(DIALOG_DOWNLOAD_PROGRESS);
		} catch (IllegalArgumentException e) {
			Log.e(TAG, "Attempting to dismiss dialog that had not been shown.");
		}
	}
	
	private void onCampaignDownloaded(String campaignUrn, CampaignReadResponse response) {
		
		mTask2 = null;
		
		if (response.getResult() == Result.SUCCESS) {
			
			// parse response
			try {
				JSONObject campaignJson = ((JSONObject)response.getData()).getJSONObject(campaignUrn);
				String name = campaignJson.getString("name");
				String creationTimestamp = campaignJson.getString("creation_timestamp");
				String xml = campaignJson.getString("xml");
				SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
				String downloadTimestamp = dateFormat.format(new Date());
				//String downloadTimestamp = DateFormat.format("yyyy-MM-dd kk:mm:ss", System.currentTimeMillis());
				
				DbHelper dbHelper = new DbHelper(this);
				if (dbHelper.getCampaign(campaignUrn) == null) {
					dbHelper.addCampaign(campaignUrn, name, creationTimestamp, downloadTimestamp, xml);
				} else {
					Log.w(TAG, "Campaign already exists. This should never happen. Replacing previous entry with new one.");
					dbHelper.removeCampaign(campaignUrn);
					dbHelper.addCampaign(campaignUrn, name, creationTimestamp, downloadTimestamp, xml);
				}
				
				
				// create an intent to fire off the feedback service
				Intent fbIntent = new Intent(this, FeedbackService.class);
				// annotate the request with the current campaign's URN
				fbIntent.putExtra("campaign_urn", campaignUrn);
				// and go!
				WakefulIntentService.sendWakefulWork(this, fbIntent);
				
			} catch (JSONException e) {
				Log.e(TAG, "Error parsing response json", e);
			}
			// update listview
			loadCampaigns();
			updateCampaignList();
			((CampaignListAdapter) getListAdapter()).notifyDataSetChanged();
		} else { 
			
			try {
				dismissDialog(DIALOG_DOWNLOAD_PROGRESS);
			} catch (IllegalArgumentException e) {
				Log.e(TAG, "Attempting to dismiss dialog that had not been shown.", e);
			}
			
			if (response.getResult() == Result.FAILURE) {
				Log.e(TAG, "Read failed due to error codes: " + Utilities.stringArrayToString(response.getErrorCodes(), ", "));
				
				boolean isAuthenticationError = false;
				boolean isUserDisabled = false;
				
				for (String code : response.getErrorCodes()) {
					if (code.charAt(1) == '2') {
						isAuthenticationError = true;
						
						if (code.equals("0201")) {
							isUserDisabled = true;
						}
					}
				}
				
				if (isUserDisabled) {
					new SharedPreferencesHelper(this).setUserDisabled(true);
					showDialog(DIALOG_USER_DISABLED);
				} else if (isAuthenticationError) {
					showDialog(DIALOG_AUTH_ERROR);
				} else {
					showDialog(DIALOG_INTERNAL_ERROR);
				}
				
			} else if (response.getResult() == Result.HTTP_ERROR) {
				Log.e(TAG, "http error");
				
				showDialog(DIALOG_NETWORK_ERROR);
			} else {
				Log.e(TAG, "internal error");
				
				showDialog(DIALOG_INTERNAL_ERROR);
			}
		}
	}

	private static class CampaignListUpdateTask extends AsyncTask<String, Void, CampaignReadResponse>{
		
		private CampaignListActivity mActivity;
		private boolean mIsDone = false;
		private CampaignReadResponse mResponse = null;
		
		private CampaignListUpdateTask(CampaignListActivity activity) {
			this.mActivity = activity;
		}
		
		public void setActivity(CampaignListActivity activity) {
			this.mActivity = activity;
			if (mIsDone) {
				notifyTaskDone();
			}
		}
		
		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			
			mActivity.mFooter.setVisibility(View.VISIBLE);
			mActivity.mFooter.findViewById(R.id.progress_bar).setVisibility(View.VISIBLE);
			mActivity.mFooter.findViewById(R.id.error_text).setVisibility(View.GONE);
		}

		@Override
		protected CampaignReadResponse doInBackground(String... params) {
			String username = params[0];
			String hashedPassword = params[1];
			OhmageApi api = new OhmageApi(mActivity);
			return api.campaignRead(SharedPreferencesHelper.DEFAULT_SERVER_URL, username, hashedPassword, "android", "short", null);
		}
		
		@Override
		protected void onPostExecute(CampaignReadResponse response) {
			super.onPostExecute(response);
			
			mResponse = response;
			mIsDone = true;
			notifyTaskDone();		
			
			// dismissing dialog from other task!!!
//			if (mActivity.mShowingProgressDialog) {
//				mActivity.dismissDialog(DIALOG_DOWNLOAD_PROGRESS);
//				mActivity.mShowingProgressDialog = false;
//			}			
		}
		
		private void notifyTaskDone() {
			if (mActivity != null) {
				mActivity.onCampaignListUpdated(mResponse);
			}
		}
	}
	
	private static class CampaignDownloadTask extends AsyncTask<String, Void, CampaignReadResponse>{
		
		private CampaignListActivity mActivity;
		private boolean mIsDone = false;
		private CampaignReadResponse mResponse = null;
		private String mCampaignUrn;
		
		private CampaignDownloadTask(CampaignListActivity activity) {
			this.mActivity = activity;
		}
		
		public void setActivity(CampaignListActivity activity) {
			this.mActivity = activity;
			if (mIsDone) {
				notifyTaskDone();
			}
		}
		
		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			
			//show progress dialog
//			mActivity.mShowingProgressDialog = true;
			mActivity.showDialog(DIALOG_DOWNLOAD_PROGRESS);
		}

		@Override
		protected CampaignReadResponse doInBackground(String... params) {
			String username = params[0];
			String hashedPassword = params[1];
			mCampaignUrn = params[2];
			OhmageApi api = new OhmageApi(mActivity);
			return api.campaignRead(SharedPreferencesHelper.DEFAULT_SERVER_URL, username, hashedPassword, "android", "long", mCampaignUrn);
		}
		
		@Override
		protected void onPostExecute(CampaignReadResponse response) {
			super.onPostExecute(response);
			
			mResponse = response;
			mIsDone = true;
			notifyTaskDone();
			
			//close progress dialog
//			mActivity.dismissDialog(DIALOG_DOWNLOAD_PROGRESS);
		}
		
		private void notifyTaskDone() {
			if (mActivity != null) {
				mActivity.onCampaignDownloaded(mCampaignUrn, mResponse);
			}
		}
	}
}
