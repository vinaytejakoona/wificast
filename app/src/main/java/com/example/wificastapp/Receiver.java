package com.example.wificastapp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Vector;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;
import com.iitb.wifimulticast.R;

@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class Receiver extends ActionBarActivity {
	public String   s_dns1 ;
	public String   s_dns2;     
	public String   s_gateway;  
	public String   s_ipAddress;    
	public String   s_leaseDuration;    
	public String   s_netmask;  
	public String   s_serverAddress;
	public String logs[]=new String[15000];
	int logcounter = 0;

	int DatagramSize = 2000;
	int WCHeaderSize = 7;
	InetAddress WCMulticastAddress = null;
	InetAddress ServerAddress = null;
	int WCSourcePort = 4567, sourcePort=0;
	int WCSinkPort = 4576, sinkStart=50;
	int currFileIndex=0,totalFile=1;
	String Filler = new String("wc"); // To fill EOT and conn packets with some data.
	long SocketTimeout = 1000;
	
	// File Variables : 
	private String fileName;
	private Vector<byte[]> fileBuffer;

	private MulticastSocket WCSocketSink = null, WCSocketFile1=null;
	private FileOutputStream outputStream = null;
	private InetAddress sourceIP = null;
	private long ConnTimeout = 500; // Timeout for CONN packet.
	private long DataTimeout = 100; // Timeout for Data packet.
	private int fileBufferSize;
	private boolean[] packetsReceived = null;
	
	private int currPacketNoSink ; // Denotes the maximum packet no received.
	private int currEOTNo; 	// Stores the current EOT packet no received.Ignores duplicates.
	private Boolean fileTransferComplete = false; // If true, file transfer is complete.
	private Boolean fileTransferStart = false; // if true, file transfer has started.
		
	private boolean[] packetSNACK = null;  // True if packet no snack is sent by someone.
	private int fileBufferNo = 0; // Points to the file buffer element to start writing.
	
	DhcpInfo d;
	File dir = null;
	File fileReceived = null;
	private TextView fileN = null;
	private TextView fileP = null;
	private String username = "wificast" ;
	//timers
	long startTime ;
	long fileTransferTime;
	ListenDataTask LDTask;
	boolean isSenderDown=false;

	String[] progress = new String[2];	//for publishing progress on the screen
	long snackTimer;  //Timer for sending SNACK
	boolean sendSnack = false;	//set to true if eof packet (type f) received
	private int MaxSnackCount = 500;	//max lost packets sent in a snack
	String hostIP;	//IP of sink
	String ackerIP;	//IP of sink supposed to send feedback
	boolean acker = false, partialComplete=false;	//set this flag for sink who sends feedback
	String lastFileReceived="";			// for checking duplicate transfer
	private Vector<String> receivedFiles=new Vector<String>();
	
	@SuppressLint("NewApi") 
	private class ListenDataTask extends AsyncTask<Void, String, Long> {
		@Override
		protected Long doInBackground(Void... arg0) {
			if(!connectSource())	// check if successfully connected
				return null;
			boolean isTransferComplete=false;
			int fileReceivedSuccessfully=0;
			while(!isTransferComplete){
				if(!acker)			// feedback response sender sends responses until last client finishes
					isTransferComplete=listenFileMetadata();		 
				else
					isTransferComplete=partialComplete;
								
				progress[0] = "Connected to server";
				progress[1] = "File Name: " + fileName + " File is stored at: '/sdCard/Wificast/";
				if(receivedFiles.size()>0)
					progress[1]+="\n"+ "Files received\n";
				for(int i=0;i<receivedFiles.size();i++)
					progress[1]+=receivedFiles.get(i)+"\n";
				publishProgress(progress);
				fileTransferComplete=false;
				listenData();   
				if(!isSenderDown){
					if(writeFile()==0){
						Log.d(ACTIVITY_SERVICE," Write failed");
					}
					fileBuffer.clear();
					fileReceivedSuccessfully++;
					receivedFiles.add(fileName);
					fileTransferTime = (System.currentTimeMillis() - startTime);
					logs[logcounter++] = "Filename "+fileName+" Total time taken : " + Long.valueOf(fileTransferTime);
					Log.d(ACTIVITY_SERVICE,"Total time taken : " + Long.valueOf(fileTransferTime));
					progress[0] = "File Transfer Complete. Time taken in milliseconds is "+ Long.valueOf(fileTransferTime);
					publishProgress(progress);
					lastFileReceived=fileName;
					sendAck(fileTransferTime);
					Log.d(ACTIVITY_SERVICE,"File transfer complete "+fileName + " "+ isTransferComplete);
					if(acker)
						partialComplete=listenEOR();
				}
				else
					isTransferComplete=true;
			}
			progress[0] = fileReceivedSuccessfully +" file(s) received successfully";
			progress[1]="";
			for(int i=0;i<receivedFiles.size();i++)
				progress[1]+=receivedFiles.get(i)+"\n";
			publishProgress(progress);
			//printLogs(); send logs to a local machine
			return null;
		}
		
		private void printLogs(){
			try {
				InetAddress addr = InetAddress.getByName("10.129.26.56");
		        Socket skt = new Socket(addr, 1234);
		        ObjectOutputStream out = new ObjectOutputStream(skt.getOutputStream());
		        out.writeObject(logs);
		        out.close();
		        skt.close();
		    }
		    catch(Exception e){  e.printStackTrace(); }
		}
		
		private boolean connectSource() {
			/* sinks initiate connections to sender
			 * wait for connection response
			 */
			int connTries = 0; // Timeout
			while(connTries < 3){
				byte[] sendBuf = createWCPacket('c', 0, username);
				DatagramPacket connPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort);  
				for(int i = 0; i < 1; ++i) {
					try {
						WCSocketSink.send(connPacket);
					} catch (IOException e) {
						Log.d(ACTIVITY_SERVICE,"Error in sending connection packet");
					}
				}
				// receive conn response
				byte[] buf = new byte[256];
		        DatagramPacket connRespPacket = new DatagramPacket(buf, buf.length);
		        try {
		        	WCSocketSink.setSoTimeout((int) ConnTimeout); // Timeout Socket to un-block at receive()
		        } catch(SocketException e) {}
				try{
					WCSocketSink.receive(connRespPacket); 
		       		MainActivity.WCPacket connP = new MainActivity.WCPacket();
		       		connP.parse(connRespPacket);
			       	if(connP.type == 'r') {
			       		//Log.d(ACTIVITY_SERVICE,"received connection response packet");
			       		progress[0] = "Connected to Server ";
						publishProgress(progress);
			       		return true;
			       	}
		        } catch (IOException e) {
					Log.d(ACTIVITY_SERVICE,"Error in receiving connection response packet");
					connTries++;
				}
		        if(connTries >= 3){
		        	if(showDialogBox())
		        		connTries=0;
		        	else
		        		return false;
		        }
			}
			return false;
		}
		
		boolean m_Input = false;
		protected synchronized boolean showDialogBox(){
			runOnUiThread(new Runnable(){
		        @Override
		        public void run(){
		        	AlertDialog.Builder builder1 = new AlertDialog.Builder(Receiver.this);
		        	builder1.setMessage("No response from Sender.");
		        	builder1.setCancelable(true);

		        	builder1.setPositiveButton("Continue",new DialogInterface.OnClickListener() {
		        	        public void onClick(DialogInterface dialog, int id) {
		        	        	m_Input = true;
		        	        	synchronized (Receiver.this) {
										Receiver.this.notify();
								}
		        	            dialog.cancel();
		        	       }
		        	});
		        	builder1.setNegativeButton("Exit",new DialogInterface.OnClickListener() {
		        	        public void onClick(DialogInterface dialog, int id) {
		        	            m_Input = false;
		        	        	dialog.cancel();
		        	        	synchronized (Receiver.this) {
									Receiver.this.notify();
								}
		        	        	Intent intent = new Intent(Receiver.this, MainActivity.class);
		        	        	intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		        	        	startActivity(intent);
		        	        }
		        	});
					AlertDialog alert11 = builder1.create();
		        	alert11.show();
		        }
			});	
		    try{
		    	 synchronized(Receiver.this) {
		    	     Receiver.this.wait();
		    	 } 
			 }
			 catch (InterruptedException e) { e.printStackTrace();  }
		     return m_Input;
		}

		private boolean listenFileMetadata() {
			int totalFiles=0;
			byte[] buf = new byte[256];
			DatagramPacket metadataPacket = new DatagramPacket(buf, buf.length);
			try {
				WCSocketSink.setSoTimeout((int) ConnTimeout); // Timeout Socket to un-block at receive()
			} catch(SocketException e) {}
			while(totalFiles==0) {
				totalFiles=processMeta(metadataPacket);
			}
			return totalFiles==1;		// denotes last transfer
		}
		
		private int processMeta(DatagramPacket metadataPacket){
			int totalFiles=0;
			try {
				WCSocketSink.receive(metadataPacket); 
				MainActivity.WCPacket metadataP = new MainActivity.WCPacket();
				metadataP.parse(metadataPacket);
				//Log.d(ACTIVITY_SERVICE, "meta" + metadataP.type);
				if(metadataP.type == 'm') {
					// Metadata Packet.	
					String[] metadataFields = new String(metadataP.dataStr).split("&");	
					fileName = metadataFields[0];
					fileBufferSize = Integer.parseInt(metadataFields[1]);
					ackerIP = metadataFields[2];	//IP of the sink who will send feedback responses
					if(lastFileReceived.compareTo(fileName)!=0){
						//Log.d(ACTIVITY_SERVICE, "metadata received ackerIP " + ackerIP + "hostIP " + hostIP);
						totalFiles=Integer.parseInt(metadataFields[3]);
						if(ackerIP.equals(hostIP))  
							acker = true;	//set this flag true for sink supposed to send feedback packets
						// Create file Buffer.
						fileBuffer = new Vector<byte[]>();
						for(int i = 0; i < fileBufferSize; ++i) {
							fileBuffer.add(null);
						}
						// Set source IP : 
						sourceIP = metadataPacket.getAddress();
						sourcePort = metadataPacket.getPort();
						// Create Packet Received Array.
						packetsReceived = new boolean[fileBufferSize];
						// SNACK listening array.
						packetSNACK = new boolean[fileBufferSize];
						fileTransferStart=true;
						startTime = System.currentTimeMillis();
						try {
							Log.d(ACTIVITY_SERVICE,"file to be stored: " + fileName + " and size of buffer : " + fileBuffer.size());
							File sdCard = Environment.getExternalStorageDirectory();
							dir = new File (sdCard.getAbsolutePath() + "/Wificast");
							dir.mkdirs();
							fileReceived = new File(dir,fileName);
							outputStream = new FileOutputStream(fileReceived);
						} catch (FileNotFoundException e) {}
						// opening new socket for each transfer
						try {
							WCSocketFile1 = new MulticastSocket(sourcePort+sinkStart);
						} catch (Exception e) {System.out.println("Exception occured");}
						try{
							WCSocketFile1.joinGroup(WCMulticastAddress);
						} catch (IOException e) {}
					}
				}	
			} catch(IOException e) {
				// Socket Timed Out. No File Metadata recvd. 
				//connectSource(); // try to connect to source again.
			}
			// TODO: Add metadata timeout
			return totalFiles;
		}
		//for acker
		private boolean listenEOR(){
			byte[] buf = new byte[DatagramSize];
			DatagramPacket dataPacket = new DatagramPacket(buf, buf.length);
			
			byte[] buff = new byte[256];
			DatagramPacket metadataPacket = new DatagramPacket(buff, buff.length);
			
			int retVal=0;
			long endTimer=System.currentTimeMillis();
			
			try {
				WCSocketFile1.setSoTimeout(10); // Timeout Socket to un-block at receive()
			} catch(SocketException e) {}
			try {
				WCSocketSink.setSoTimeout(1); // Timeout Socket to un-block at receive()
			} catch(SocketException e) {}
			//sending feedback after file completion
			while(true){
				try{
					WCSocketFile1.receive(dataPacket);
					MainActivity.WCPacket packet = new MainActivity.WCPacket();
					packet.parse(dataPacket);
					endTimer=System.currentTimeMillis();
					if(packet.type=='b'){
						int lastPacket = Integer.parseInt(new String(packet.data)) ;
						byte[] sendBuf = createWCPacket('b', 0, Integer.toString(lastPacket)); 
						DatagramPacket feedbackPacket = new DatagramPacket(sendBuf, sendBuf.length, sourceIP, sourcePort);  
						try {
							WCSocketFile1.send(feedbackPacket);
							//Log.d(ACTIVITY_SERVICE,"eor response sent");
							logs[logcounter++] = "eor response sent " + lastPacket + "  " + (System.currentTimeMillis() - startTime);
						} catch (IOException e) { Log.d(ACTIVITY_SERVICE, "Error in sending feedback"); }
					}
				}catch(IOException e) {
					//listening for meta
					retVal=processMeta(metadataPacket);
					
					if(!partialComplete && retVal>0)
						return retVal==1;
				}
				if(System.currentTimeMillis()-endTimer > 10000)
					return true;
			}
		}
		
		private void listenData() {
			long duration = 250;		//wait for 100 ms before sending snack
			int countContinousSnack=0;
			byte[] buf = new byte[DatagramSize];
			DatagramPacket dataPacket = new DatagramPacket(buf, buf.length);
			try {
				WCSocketFile1.setSoTimeout((int) DataTimeout); // Timeout Socket to un-block at receive()
			} catch(SocketException e) {}

			Log.d(ACTIVITY_SERVICE,"socket opened");
			snackTimer = System.currentTimeMillis();
			while(!fileTransferComplete && !isSenderDown) {
				try {
					WCSocketFile1.receive(dataPacket);
					process(dataPacket);
					countContinousSnack=0;
					snackTimer = System.currentTimeMillis();
				} 
				catch(IOException e) { }
				//no packet received . send snack
				if(fileTransferStart && (sendSnack || (System.currentTimeMillis() - snackTimer >= duration))){
					sendSNACK();
					countContinousSnack++;
				 	snackTimer = System.currentTimeMillis();
				 	sendSnack = false;
				}
				if(countContinousSnack==10 || (System.currentTimeMillis()- snackTimer) >= 10000){
					//Log.d(ACTIVITY_SERVICE,"sender not responding");
					if(!showDialogBox())
						isSenderDown=true;			//return to home screen
					else
						countContinousSnack=0;
				}
			}
		}

		/*
		 *	Packet Type : currTime # Packet No # Total Packets(in this time window) # Random Characters
		 * 	Packet No. = -1 for EOT packet and Total Packets(till in this time Window)
		 */
		public void process(DatagramPacket p) {
			MainActivity.WCPacket packet = new MainActivity.WCPacket();
			packet.parse(p);
			//parsing packet according to type
			// EOT Packet Check.
			if(packet.type == 'e'){
				// Check if eot packet is duplicate
				if(currEOTNo != packet.no ) {
					// Not a duplicate EOT packet.
					currEOTNo = packet.no; // Set Curr EOT packet no.
				}
			}
			else if(packet.type == 'f'){	//eof packet .. now send snacks
				logs[logcounter++] = "eof received " + (System.currentTimeMillis() - startTime);
				sendSnack = true;
			}
			//send feedback response if acker flag set and feedback request packet reeived
			else if(acker && packet.type == 'b'){
				int lastPacket = Integer.parseInt(new String(packet.data)) ;
				byte[] sendBuf = createWCPacket('b', 0, Integer.toString(lastPacket)); 
				DatagramPacket feedbackPacket = new DatagramPacket(sendBuf, sendBuf.length, sourceIP, sourcePort);  
				try {
						WCSocketFile1.send(feedbackPacket);
						//Log.d(ACTIVITY_SERVICE, "feedback sent to " + sourceIP) ;
						logs[logcounter++] = "eor response sent " + lastPacket + "  " + (System.currentTimeMillis() - startTime);
				} catch (IOException e) { Log.d(ACTIVITY_SERVICE, "Error in sending feedback"); }
			}
			else if(packet.type == 'd') {  //data packet
				// To simulate losses
				if(!fileTransferStart) {
					fileTransferStart = true;
					startTime = System.currentTimeMillis();
				}
				logs[logcounter++] = "datapacketreceived " + packet.no + " time " + (System.currentTimeMillis() - startTime);
				//Log.d(ACTIVITY_SERVICE,"data packet is received " + packet.no+ "time "+(System.currentTimeMillis()-startTime));
				// Data Packet
				int packetNo = packet.no;
				if(packetsReceived[packetNo] == true) { /* Dup Packet. Ignore. */ 	}
				else {
					currPacketNoSink = Math.max(currPacketNoSink, packetNo);
					packetsReceived[packetNo] = true;
					// Add to file buffer.
					fileBuffer.set(packetNo, packet.data);
				}
			}
			else { /* Ignore other packets.*/ }
		}
		
		private void sendSNACK() {
				//find out what are snack packets
				String packetNoSnack = "";
				// Add Header to SNACK packet.
				int countSNACK = 0;
				int lostCount = 0;
				int no = Math.min(currPacketNoSink, fileBuffer.size() - 1);
				for(int i = no; i >= 0; --i) {
					if(packetsReceived[i] == false) {
						lostCount++;
					}
				}
				for(int i = 0; i <= no; ++i) {
					if(packetsReceived[i] == false && packetSNACK[i] == false) {
						// Packet not recvd and no one has yet SNACKed this.
						packetNoSnack += i + "&";
						countSNACK++;
						if(countSNACK == MaxSnackCount) break;
					}	
				}
				
				if(lostCount == 0) {
					// There are no missing packets.
					if(currPacketNoSink >= fileBufferSize - 1) {
						fileTransferComplete = true;
						return;
					}
					else {
						currPacketNoSink = Math.min(fileBufferSize-1, currPacketNoSink+100);
					}
				}
				//sending snack
				if(lostCount != 0) {
					progress[0] = "Lost Count is " + lostCount + " Current Packet No is " + currPacketNoSink;
					publishProgress(progress);
					packetNoSnack = lostCount + "&" + packetNoSnack;
					logs[logcounter++] = "Sending Snack now "  + (System.currentTimeMillis() - startTime) + "  "+ lostCount;
					byte[] sendBuf = createWCPacket('s', 0, packetNoSnack); 
					//DatagramPacket snackPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSourcePort); 
					//unicast snack
					DatagramPacket snackPacket = new DatagramPacket(sendBuf, sendBuf.length, sourceIP, sourcePort);
					for(int i = 0; i < 1; ++i) { 
						try {
							WCSocketFile1.send(snackPacket);
							//Log.d(ACTIVITY_SERVICE,"Sending Snack now "+packetNoSnack+" LC: "+lostCount+" pcno "+currPacketNoSink + " Time "+(System.currentTimeMillis()-startTime));
						} catch (IOException e) { System.out.println("Error in sending SNACK"); }
					}
				}
				// Reset the packetSNACK array.
				for(int i = no; i >= 0; --i) {
					packetSNACK[i] = false;
				}
		}
		
		private void sendAck(long fileTransferTime) {
			// sending ack after file completion
			String packetAck = Integer.toString((int) fileTransferTime);
			byte[] sendBuf = createWCPacket('a', 0, packetAck); 
			//unicast ACK
			DatagramPacket ackPacket = new DatagramPacket(sendBuf, sendBuf.length,  sourceIP, sourcePort);  
			for(int i = 0; i < 20; ++i) { 
				try {
					WCSocketFile1.send(ackPacket);
					logs[logcounter++] = "ack sent " + (System.currentTimeMillis() - startTime);
				} catch (IOException e) { System.out.println("Error in sending ACK"); }
			}
		}	
		
		protected void onProgressUpdate(String... progress) {
			fileN.setText(progress[0]);
			fileP.setText(progress[1]);
		}
	}

	@SuppressLint("NewApi") @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_progress_bar);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		//		setContentView(R.layout.activity_receiver);

		Intent intent = getIntent();
		username = intent.getStringExtra(MainActivity.EXTRA_MESSAGE);

		WifiManager wifi = (WifiManager)getSystemService( Context.WIFI_SERVICE );
		if(wifi != null){
			WifiManager.MulticastLock lock = wifi.createMulticastLock("WifiDevices");
			lock.acquire();
			WifiLock lock1 = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LockTag");
            lock1.acquire();
		}

		if (android.os.Build.VERSION.SDK_INT > 9) {
			StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
			StrictMode.setThreadPolicy(policy);
		}

		d=wifi.getDhcpInfo();
		try {
			hostIP = InetAddress.getByName(intToIp(d.ipAddress)).toString();	//IP address of the sink
			//ServerAddress = InetAddress.getByName(intToIp(d.serverAddress));
			Log.d(ACTIVITY_SERVICE, "host address " + hostIP );
			//WCMulticastAddress = InetAddress.getByName(getServerMultiCastAddress(d.serverAddress));
			WCMulticastAddress = InetAddress.getByName("224.0.168.1");
		} catch (UnknownHostException e) {  e.printStackTrace();  }
		//joining multicast group
		try {
			WCSocketSink = new MulticastSocket(WCSinkPort);
		} catch (Exception e) {System.out.println("Exception occured");}
		try{
			WCSocketSink.joinGroup(WCMulticastAddress);
		} catch (IOException e) {}

		fileN = (TextView) findViewById(R.id.textView1);
		fileP = (TextView) findViewById(R.id.textView2);
		fileN.setText("Trying to connect to source");

		LDTask = new ListenDataTask();  
		//SSTask = new SendSnackTask();
		LDTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.receiver, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private int writeFile() {
		//writing file to memory
		for(int i = fileBufferNo; i < fileBuffer.size(); ++i) {
			if(fileBuffer.get(i) != null) {
				try { 
					outputStream.write(fileBuffer.get(i));
					fileBuffer.set(i, null);
				} catch (IOException e) {
					return 0;
				}	
			} 
			else {
				// Hole in the filebuffer. Break;
				fileBufferNo = i;
				return 0;
			}
		}
		return 1;
	}
	
	public byte[] createWCPacket(char t, int n, String d) {
		String packetStr = t + "#" + Integer.toString(n) + "#" + d;
		return packetStr.getBytes();
	}

	public byte[] createWCPacket(char t, int n, byte[] d) {
		String ph1 = t + "#" + Integer.toString(n) + "#";
		byte[] packet = new byte[ph1.getBytes().length + d.length];
		System.arraycopy(ph1.getBytes(), 0, packet, 0, ph1.getBytes().length);
		System.arraycopy(d, 0, packet, ph1.getBytes().length, d.length);
		return packet;
	} 

	public String intToIp(int i) {
		return ( i & 0xFF) + "." + ((i >> 8 ) & 0xFF) + "." + ((i >> 16 ) & 0xFF) + "." + ((i >> 24 ) & 0xFF ) ;
	}

	public String getServerMultiCastAddress (int i){
		return "224.0." +    
				((i >> 8 ) & 0xFF) + "." +    
				((i >> 24 ) & 0xFF ) ;
	}
}