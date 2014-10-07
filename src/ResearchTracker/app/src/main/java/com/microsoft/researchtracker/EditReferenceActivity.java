package com.microsoft.researchtracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.microsoft.researchtracker.data.ResearchDataSource;
import com.microsoft.researchtracker.sharepoint.SPUrl;
import com.microsoft.researchtracker.sharepoint.models.ResearchProjectModel;
import com.microsoft.researchtracker.sharepoint.models.ResearchReferenceModel;
import com.microsoft.researchtracker.utils.AsyncUtil;
import com.microsoft.researchtracker.utils.AuthUtil;
import com.microsoft.researchtracker.utils.ViewUtil;
import com.microsoft.researchtracker.utils.auth.DefaultAuthHandler;

import java.util.List;

public class EditReferenceActivity extends Activity {

    private static final String TAG = "EditReferenceActivity";

    public static final String PARAM_NEW_REFERENCE_MODE = "new_reference_enabled";
    public static final String PARAM_NEW_REFERENCE_URL = "new_reference_url";
    public static final String PARAM_NEW_REFERENCE_TITLE = "new_reference_title";
    public static final String PARAM_NEW_REFERENCE_DESCRIPTION = "new_reference_description";

    public static final String PARAM_PROJECT_ID = "project_id";
    public static final String PARAM_REFERENCE_ID = "reference_id";

    private App mApp;

    private EditText mUrlText;
    private EditText mTitleText;
    private EditText mDescriptionText;
    private TextView mProjectLabel;
    private Spinner mProjectSpinner;
    private ProgressBar mProgress;

    private boolean mLoaded;

    private boolean mIsNewReference;
    private ResearchReferenceModel mModel;
    private List<ResearchProjectModel> mAvailableProjects;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_reference);

        mApp = (App) getApplication();

        mUrlText = (EditText) findViewById(R.id.url_edit_text);
        mUrlText.setEnabled(false);

        mTitleText = (EditText) findViewById(R.id.title_edit_text);
        mTitleText.setEnabled(false);

        mDescriptionText = (EditText) findViewById(R.id.description_edit_text);
        mDescriptionText.setEnabled(false);

        mProjectLabel = (TextView) findViewById(R.id.project_label);
        mProjectLabel.setVisibility(View.GONE);

        mProjectSpinner = (Spinner) findViewById(R.id.project_spinner);
        mProjectSpinner.setVisibility(View.GONE);

        mProgress = (ProgressBar) findViewById(R.id.progress);
        mProgress.setVisibility(View.GONE);

        mModel = null;
        mAvailableProjects = null;

        mIsNewReference = false;
        mLoaded = false;
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!mLoaded) {
            mLoaded = true;

            /*
                This activity has three modes of operation:

                1.  Edit existing reference mode:
                    Retrieve the reference details and populate the view with them.
                    Hide the Project spinner.

                2.  Create new reference (with given Project id)
                    Clear the view.
                    Hide the Project spinner.

                3.  Create new reference (without initial Project id)
                    Retrieve the details of all available projects.
                    Clear the view.
                    Populate and show the Project spinner.

                The save operation is the same for each mode.

            */

            final int MISSING_ID = -1;

            Intent launchIntent = getIntent();

            if (launchIntent.getBooleanExtra(PARAM_NEW_REFERENCE_MODE, false)) {
                //We've been asked to create a new Reference...
                mIsNewReference = true;

                //Intialize the model from passed in arguments
                SPUrl url = new SPUrl();
                url.setUrl(launchIntent.getStringExtra(PARAM_NEW_REFERENCE_URL));
                url.setTitle(launchIntent.getStringExtra(PARAM_NEW_REFERENCE_TITLE));

                mModel = new ResearchReferenceModel();
                mModel.setURL(url);
                mModel.setProjectId(launchIntent.getIntExtra(PARAM_PROJECT_ID, MISSING_ID));
                mModel.setDescription(launchIntent.getStringExtra(PARAM_NEW_REFERENCE_DESCRIPTION));

                if (mModel.getProjectId() == MISSING_ID) {
                    //We don't have a project ID yet - retrieve the list of available projects, and then prepare the view
                    retrieveProjectsAndPrepareView();
                }
                else {
                    //We've got everything we need - prepare the view
                    prepareView();
                }
            }
            else {
                //We're editing an existing reference.
                //Retrieve the details and render them to the screen
                int referenceId = launchIntent.getIntExtra(PARAM_REFERENCE_ID, MISSING_ID);
                retrieveReferenceDetailsAndPrepareView(referenceId);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.edit_reference, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_accept) {
            saveChangesAndFinish();
            return true;
        }
        if (id == R.id.action_cancel) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void ensureAuthenticated(final Runnable r) {
        AuthUtil.ensureAuthenticated(this, new DefaultAuthHandler(this) {
            public void onSuccess() {
                r.run();
            }
        });
    }

    private void prepareView() {

        SPUrl url = mModel.getURL();

        mUrlText.setText(url == null ? null : url.getUrl());
        mUrlText.setEnabled(true);

        mTitleText.setText(url == null ? null : url.getTitle());
        mTitleText.setEnabled(true);

        mDescriptionText.setText(mModel.getDescription());
        mDescriptionText.setEnabled(true);

        //Show the project selector spinner?
        if (mAvailableProjects != null) {
            mProjectLabel.setVisibility(View.VISIBLE);
            mProjectSpinner.setVisibility(View.VISIBLE);
            mProjectSpinner.setAdapter(new ProjectReferencesAdapter(mAvailableProjects));
        }

        //Try and pick a textbox to focus on by default
        if (mUrlText.getText().length() == 0) {
            mUrlText.requestFocus();
        }
        else if (mTitleText.getText().length() == 0) {
            mTitleText.requestFocus();
        }
        else if (mDescriptionText.getText().length() == 0) {
            mDescriptionText.requestFocus();
        }
    }

    private void retrieveReferenceDetailsAndPrepareView(final int referenceId) {

        ensureAuthenticated(new Runnable() {
            public void run() {

                mProgress.setVisibility(View.VISIBLE);

                AsyncUtil.onBackgroundThread(new AsyncUtil.BackgroundHandler<ResearchReferenceModel>() {
                    public ResearchReferenceModel run() {
                        try {
                            return mApp.getDataSource().getResearchReferenceById(referenceId);
                        }
                        catch (Exception e) {
                            Log.e(TAG, "Error retrieving project", e);
                            return null;
                        }
                    }
                })
                .thenOnUiThread(new AsyncUtil.ResultHandler<ResearchReferenceModel>() {
                    public void run(ResearchReferenceModel model) {

                        mProgress.setVisibility(View.GONE);

                        if (model == null) {

                            new AlertDialog.Builder(EditReferenceActivity.this)
                                    .setTitle(R.string.dialog_generic_error_title)
                                    .setMessage(R.string.dialog_generic_error_message)
                                    .setNegativeButton(R.string.label_go_back, null)
                                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                        public void onCancel(DialogInterface dialog) {
                                            finish();
                                        }
                                    })
                                    .create()
                                    .show();
                            return;

                        }

                        mModel = model;
                        prepareView();
                    }
                })
                .execute();
            }
        });

    }

    private void retrieveProjectsAndPrepareView() {

        ensureAuthenticated(new Runnable() {
            public void run() {

                mProgress.setVisibility(View.VISIBLE);

                AsyncUtil.onBackgroundThread(new AsyncUtil.BackgroundHandler<List<ResearchProjectModel>>() {
                    public List<ResearchProjectModel> run() {
                        try {
                            return mApp.getDataSource().getResearchProjects();
                        }
                        catch (Exception e) {
                            Log.e(TAG, "Error retrieving project", e);
                            return null;
                        }
                    }
                })
                .thenOnUiThread(new AsyncUtil.ResultHandler<List<ResearchProjectModel>>() {
                    public void run(final List<ResearchProjectModel> projects) {

                        mProgress.setVisibility(View.GONE);

                        if (projects == null) {
                            new AlertDialog.Builder(EditReferenceActivity.this)
                                    .setTitle(R.string.dialog_generic_error_title)
                                    .setMessage(R.string.dialog_generic_error_message)
                                    .setNegativeButton(R.string.label_go_back, null)
                                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                        public void onCancel(DialogInterface dialog) {
                                            finish();
                                        }
                                    })
                                    .create()
                                    .show();

                            return;
                        }

                        //Got the projects!
                        mAvailableProjects = projects;
                        prepareView();
                    }
                })
                .execute();
            }
        });

    }

    private boolean validateForm() {

        boolean ok = true;

        //Url
        String url = mUrlText.getText().toString();

        if (TextUtils.isEmpty(url)) {
            mUrlText.setError(getText(R.string.validation_error_required));
            ok = false;
        }
        else if (!Patterns.WEB_URL.matcher(url).matches()) {
            mUrlText.setError(getText(R.string.validation_error_must_be_url));
            ok = false;
        }
        else {
            mUrlText.setError(null);
        }

        //Project
        if (mProjectSpinner.getSelectedItem() == null) {
            mProjectLabel.setError(getText(R.string.validation_error_required));
        }
        else {
            mProjectLabel.setError(null);
        }

        return ok;
    }

    private void saveChangesAndFinish() {

        if (!validateForm()) {
            return;
        }

        ensureAuthenticated(new Runnable() {
            public void run() {

                mProgress.setVisibility(View.VISIBLE);
                mUrlText.setEnabled(false);
                mTitleText.setEnabled(false);
                mDescriptionText.setEnabled(false);

                AsyncUtil.onBackgroundThread(new AsyncUtil.BackgroundHandler<Boolean>() {
                    public Boolean run() {
                        try {
                            final ResearchDataSource data = mApp.getDataSource();
                            final ResearchReferenceModel model = new ResearchReferenceModel();

                            SPUrl url = new SPUrl();

                            url.setUrl(mUrlText.getText().toString());
                            url.setTitle(mTitleText.getText().toString());

                            model.setURL(url);
                            model.setDescription(mDescriptionText.getText().toString());

                            ResearchProjectModel project = (ResearchProjectModel) mProjectSpinner.getSelectedItem();

                            if (project != null) {
                                model.setProjectId(project.getId());
                            }
                            else {
                                model.setProjectId(mModel.getProjectId());
                            }

                            if (mIsNewReference) {
                                data.createResearchReference(model);
                            }
                            else {
                                data.updateResearchReference(mModel.getId(), mModel.getODataETag(), model);
                            }

                            return true;
                        }
                        catch (Exception e) {
                            Log.e(TAG, "Error saving reference changes", e);
                            return false;
                        }
                    }
                })
                .thenOnUiThread(new AsyncUtil.ResultHandler<Boolean>() {
                    public void run(Boolean success) {

                        mProgress.setVisibility(View.GONE);
                        mUrlText.setEnabled(true);
                        mTitleText.setEnabled(true);
                        mDescriptionText.setEnabled(true);

                        if (success) {

                            int resourceId =
                                (mIsNewReference)
                                    ? R.string.activity_edit_reference_created_message
                                    : R.string.activity_edit_reference_updated_message;

                            Toast.makeText(EditReferenceActivity.this, resourceId, Toast.LENGTH_LONG).show();

                            setResult(RESULT_OK);
                            finish();
                        }
                        else {
                            new AlertDialog.Builder(EditReferenceActivity.this)
                                    .setTitle(R.string.dialog_generic_error_title)
                                    .setMessage(R.string.dialog_generic_error_message)
                                    .setNeutralButton(R.string.label_continue, null)
                                    .create()
                                    .show();
                        }
                    }
                })
                .execute();

            }
        });

    }

    private class ProjectReferencesAdapter extends BaseAdapter {

        private final List<ResearchProjectModel> mItems;
        private final LayoutInflater mInflator;

        public ProjectReferencesAdapter(List<ResearchProjectModel> models) {
            mItems = models;
            mInflator = getLayoutInflater();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView text = (TextView) ViewUtil.prepareView(mInflator, android.R.layout.simple_spinner_item, convertView, parent);

            ResearchProjectModel model = mItems.get(position);
            text.setText(model.getTitle());

            return text;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView text = (TextView) ViewUtil.prepareView(mInflator, android.R.layout.simple_spinner_dropdown_item, convertView, parent);

            ResearchProjectModel model = mItems.get(position);
            text.setText(model.getTitle());

            return text;
        }
    }
}
