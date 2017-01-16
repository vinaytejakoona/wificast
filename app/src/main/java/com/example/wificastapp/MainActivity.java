package com.example.wificastapp;

import java.net.DatagramPacket;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import com.iitb.wifimulticast.R;

public class MainActivity extends ActionBarActivity {
	public final static String EXTRA_MESSAGE = "USERNAME";
	
	public String usertype = "receiver";
	
	public static class WCPacket {
		public char type;
		public int no;
		public byte[] data;
		public String dataStr;

		public void parse(DatagramPacket packet) {
			String packetStr = new String(packet.getData(), 0, packet.getLength());
//			System.out.println(packetStr);
			String[] fields = packetStr.split("#", 3);
			type = fields[0].charAt(0);
			no = Integer.parseInt(fields[1]);
			dataStr = new String(fields[2]);
			int headerSize = 1 + 1 + fields[1].length() + 1;
			data = new byte[packet.getLength() - headerSize];
			System.arraycopy(packet.getData(), headerSize, data, 0, packet.getLength() - headerSize);
		}
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		RadioGroup group1 = (RadioGroup) findViewById(R.id.radioGroup1);
		group1.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
		  @Override
		  public void onCheckedChanged(RadioGroup group, int checkedId) {
		    if (checkedId == R.id.radio0) {
		       usertype = "receiver";
		    }  
		    else if(checkedId == R.id.radio1){
		    	usertype = "sender";
		    }
		  }
		}); 
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
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
	
	public void chooseUserType(View view) {
		if(usertype.equals("sender")){
			Intent intent = new Intent(this, Sender.class);
			EditText editText = (EditText) findViewById(R.id.editText1);
			String username = editText.getText().toString();
			intent.putExtra(EXTRA_MESSAGE, username);
		    startActivity(intent);
		}else{
			Intent intent = new Intent(this, Receiver.class);
			EditText editText = (EditText) findViewById(R.id.editText1);
			String username = editText.getText().toString();
			intent.putExtra(EXTRA_MESSAGE, username);
		    startActivity(intent);
		}

	}
}
