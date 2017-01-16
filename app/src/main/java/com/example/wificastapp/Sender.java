package com.example.wificastapp;

import java.io.File;
import java.util.ArrayList;

import android.support.v7.app.ActionBarActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.iitb.wifimulticast.R;

public class Sender extends ActionBarActivity {
	
	private static final int REQUEST_PATH = 1;
    String curFileName, curFilePath; 
    ArrayList<String> files_paths = new ArrayList<String>();
    EditText edittext;
    TextView filePath;
    Button button;
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_sender);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		Intent intent = getIntent();
		String username = intent.getStringExtra(MainActivity.EXTRA_MESSAGE);
		edittext = (EditText)findViewById(R.id.editText);
		button = (Button)findViewById(R.id.button1);
		button.setEnabled(false);
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.sender, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
    public void getfile(View view){
        Intent intent1 = new Intent(this, FileSelectionActivity.class);
        startActivityForResult(intent1,REQUEST_PATH);
    }
    
    public void startListeningConnection(View view){
    	Intent intent = new Intent(this, SenderConnection.class);
        intent.putStringArrayListExtra("files_paths", files_paths);
	    startActivity(intent);
    }
    
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        // See which child activity is calling us back.
    	String fileNames = "";
    	files_paths.clear();
    	runOnUiThread(new Runnable(){
	        @Override
	        public void run() {
	        	button.setEnabled(false);
	        }
   		});
        if (requestCode == REQUEST_PATH){
                if (resultCode == RESULT_OK) {
                   ArrayList<File> Files = (ArrayList<File>) data.getSerializableExtra("FILES_TO_UPLOAD"); //file array list
                   Log.d(ACTIVITY_SERVICE,"hello: getSerializableExtra  " + Files.get(0));
                        for(File file : Files){
                            //String fileName = file.getName();
                        	String uri = file.getAbsolutePath();
                            files_paths.add(uri.toString()); //storing the selected file's paths to string array files_paths
                            fileNames = fileNames + uri + "\n";
                       }
                    }
                if(files_paths.size()>0){
		       		runOnUiThread(new Runnable(){
				        @Override
				        public void run() {
				        	button.setEnabled(true);
				        }
		       		});
	       		}
                
                edittext.setText(fileNames);
        }
    }
}
