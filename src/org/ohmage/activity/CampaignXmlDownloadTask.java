package org.ohmage.activity;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.json.JSONException;
import org.ohmage.NotificationHelper;
import org.ohmage.OhmageApi;
import org.ohmage.OhmageApi.CampaignReadResponse;
import org.ohmage.OhmageApi.Response;
import org.ohmage.R;
import org.ohmage.SharedPreferencesHelper;
import org.ohmage.Utilities;
import org.ohmage.OhmageApi.CampaignXmlResponse;
import org.ohmage.OhmageApi.Result;
import org.ohmage.db.DbContract.Campaigns;
import org.ohmage.db.Models.Campaign;
import org.ohmage.feedback.FeedbackService;
import org.ohmage.triggers.glue.TriggerFramework;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.widget.Toast;

import com.commonsware.cwac.wakeful.WakefulIntentService;
import com.slezica.tools.async.ManagedAsyncTask;

class CampaignXmlDownloadTask extends ManagedAsyncTask<String, Void, Response>{
	
	private static final String TAG = "CampaignXmlDownloadTask";
		
	private Context mContext;
	private String mCampaignUrn;
	
	public CampaignXmlDownloadTask(FragmentActivity activity, String campaignUrn) {
		super(activity);
		mContext = activity.getApplicationContext();
		mCampaignUrn = campaignUrn;
	}

	@Override
	protected void onPreExecute() {
		super.onPreExecute();

		ContentResolver cr = mContext.getContentResolver();
		ContentValues values = new ContentValues();
		values.put(Campaigns.CAMPAIGN_STATUS, Campaign.STATUS_DOWNLOADING);
		cr.update(Campaigns.CONTENT_URI, values, Campaigns.CAMPAIGN_URN + "= '" + mCampaignUrn + "'", null); 
	}

	@Override
	protected Response doInBackground(String... params) {
		String username = (String) params[0];
		String hashedPassword = (String) params[1];
		OhmageApi api = new OhmageApi(mContext);
		ContentResolver cr = mContext.getContentResolver();

		CampaignReadResponse campaignResponse = api.campaignRead(SharedPreferencesHelper.DEFAULT_SERVER_URL, username, hashedPassword, "android", "short", mCampaignUrn);

		if(campaignResponse.getResult() == Result.SUCCESS) {
			ContentValues values = new ContentValues();
			// Update campaign created timestamp when we download xml
			try {
				values.put(Campaigns.CAMPAIGN_CREATED, campaignResponse.getData().getJSONObject(mCampaignUrn).getString("creation_timestamp"));
				cr.update(Campaigns.buildCampaignUri(mCampaignUrn), values, null, null);
			} catch (JSONException e) {
				Log.e(TAG, "Error parsing json data for " + mCampaignUrn, e);
			}
		} else {
			return campaignResponse;
		}

		CampaignXmlResponse response =  api.campaignXmlRead(SharedPreferencesHelper.DEFAULT_SERVER_URL, username, hashedPassword, "android", mCampaignUrn);
		
		if (response.getResult() == Result.SUCCESS) {
			
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			String downloadTimestamp = dateFormat.format(new Date());
		
			ContentValues values = new ContentValues();
			values.put(Campaigns.CAMPAIGN_URN, mCampaignUrn);
			values.put(Campaigns.CAMPAIGN_DOWNLOADED, downloadTimestamp);
			values.put(Campaigns.CAMPAIGN_CONFIGURATION_XML, response.getXml());
			values.put(Campaigns.CAMPAIGN_STATUS, Campaign.STATUS_READY);
			int count = cr.update(Campaigns.CONTENT_URI, values, Campaigns.CAMPAIGN_URN + "= '" + mCampaignUrn + "'", null); 
			if (count < 1) {
				//nothing was updated
			} else if (count > 1) {
				//too many things were updated
			} else {
				//update occurred successfully
			}
			
			if (SharedPreferencesHelper.ALLOWS_FEEDBACK) {
				// create an intent to fire off the feedback service
				Intent fbIntent = new Intent(mContext, FeedbackService.class);
				// annotate the request with the current campaign's URN
				fbIntent.putExtra("campaign_urn", mCampaignUrn);
				// and go!
				WakefulIntentService.sendWakefulWork(mContext, fbIntent);
			}
		} else { 
			ContentValues values = new ContentValues();
			values.put(Campaigns.CAMPAIGN_STATUS, Campaign.STATUS_REMOTE);
			cr.update(Campaigns.CONTENT_URI, values, Campaigns.CAMPAIGN_URN + "= '" + mCampaignUrn + "'", null); 
		}
		
		return response;
	}
	
	@Override
	protected void onPostExecute(Response response) {
		super.onPostExecute(response);
		
		if (response.getResult() == Result.SUCCESS) {
			// setup initial triggers for this campaign
			TriggerFramework.setDefaultTriggers(getActivity(), mCampaignUrn);
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
				new SharedPreferencesHelper(mContext).setUserDisabled(true);
			}
			
			if (isAuthenticationError) {
				NotificationHelper.showAuthNotification(mContext);
				Toast.makeText(mContext, R.string.campaign_xml_auth_error, Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(mContext, R.string.campaign_xml_unexpected_response, Toast.LENGTH_SHORT).show();
			}
			
		} else if (response.getResult() == Result.HTTP_ERROR) {
			Log.e(TAG, "http error");
			
			Toast.makeText(mContext, R.string.campaign_xml_network_error, Toast.LENGTH_SHORT).show();
		} else {
			Log.e(TAG, "internal error");
			
			Toast.makeText(mContext, R.string.campaign_xml_internal_error, Toast.LENGTH_SHORT).show();
		} 
	}
}