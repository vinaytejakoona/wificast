package com.example.wificastapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Vector;
import java.net.Socket;

import android.support.v7.app.ActionBarActivity;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import com.iitb.wifimulticast.R;

@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class SenderConnection extends ListActivity {
	// implementation specific variables
	DhcpInfo d;
	String FileName, FilePath;    
	int DatagramSize = 1460;
    int WCHeaderSize = 7;
    InetAddress WCMulticastAddress;
	int WCSourcePort = 4567,sourceStartPort=4701;
	int WCSinkPort = 4576, sinkStart = 50;   
	
	int currFileIndex=0,totalFile=1;
	String Filler = new String("wc"); // To fill EOT and conn packets with some data.
	long SocketTimeout = 50;
	
	private MulticastSocket WCSocketSource = null, WCSocketFile=null;
	private Vector<SinkStats> sinkList;
	private SinkArrayAdapter adapter;
	private SinkStats s ;
	ArrayList<String> files_paths = new ArrayList<String>();
    // Retransmission Variables 
    private Vector<Integer> retransmissionQueue;
    private int RetryCount = 3;   //  retries for redundancy required for multicast packets
	private int windowSize = 150;  // round size
	
	private TextView currPacketNoDisp, fileSentName ; 
	private int totalSnacks=0;   // total snack responses sent
	long startTime;             //timer for printing logs
	String logs[] = new String[10000];   // collecting logs
	int logcounter = 0;   // used for indexing logs[]
	long interRoundDelay=0;
	
	private Vector<byte[]> fileBuffer;
	//counting number of finished clients
	int currPacketNo = 0;
	int countPackets = 0;
	int finClients=0;
	int totalClients=0;
	long isTransferCompleteTimer=0;
	
	private Thread listenThread;
	private File file;
	private boolean fileTransferComplete = false;
	private Boolean fileTransferStart = false;
	
	Button button;
			
	private class SendDataTask extends AsyncTask<Void, Integer, Long> {
		 	SendDataTask() {}
			@Override
			protected Long doInBackground(Void... arg0) {
				for(currFileIndex=0; currFileIndex<totalFile; currFileIndex++){
					FilePath = files_paths.get(currFileIndex);
					FileName = FilePath.substring(FilePath.lastIndexOf('/')+1);
					file = new File(FilePath);			// get current file
					retransmissionQueue =  new Vector<Integer>();	//initialize retransmission queue
					fileTransferComplete = false;					//update variables
					finClients=0;
					updateSinkStats();	
					totalSnacks=0;
			
					Log.d(ACTIVITY_SERVICE," file..." + files_paths + " "+ currFileIndex + " total file "+ totalFile);
					Log.d(ACTIVITY_SERVICE,"path of file..." + FilePath + "   " + FileName +" snacks "+ totalSnacks);
					bufferFile();
					//Log.d(ACTIVITY_SERVICE,"before BUFFER");
					if(fileBuffer.size()!=0){
						//start data transfer phase
						startTransfer();
				        sendMetadata();
				        Log.d(ACTIVITY_SERVICE,"sending file");
						startFileTransfer();	
						// end of data transfer phase, start retransmission phase
						logs[logcounter++] = "Retransmisson start "+(System.currentTimeMillis()-startTime); 
						completePendingTransfer();
						// transfer complete 
				        logs[logcounter++] = "Retransmisson end "+(System.currentTimeMillis()-startTime); 
				        logs[logcounter++] = "total SNACKS sent : "+totalSnacks+" Time: "+(System.currentTimeMillis()-startTime);
				        Log.d(ACTIVITY_SERVICE, "total SNACKS sent : "+totalSnacks+" Time:"+(System.currentTimeMillis()-startTime));
				        updateAdapter();		//update status on UI
					}
				}
				//printlogs(); send logs to a local machine via TCP
				backToStart();		//start new transfer or exit
				return (long) 0;
			}
			
			protected void backToStart(){
				/* to return to home screen after file transfer complete
				 *  or start a new transfer
				 */
				runOnUiThread(new Runnable(){
			        @Override
			        public void run(){
			        	AlertDialog.Builder builder1 = new AlertDialog.Builder(SenderConnection.this);
			        	builder1.setMessage("File Transfer Completed.");
			        	builder1.setCancelable(true);

			        	builder1.setNegativeButton("Exit",new DialogInterface.OnClickListener() {
			        	        public void onClick(DialogInterface dialog, int id) {
			        	           	dialog.cancel();
			        	        	Intent intent = new Intent(SenderConnection.this, MainActivity.class);
			        	        	intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			        	        	startActivity(intent);
			        	        }
			        	});

			        	builder1.setPositiveButton("Start New Transfer",new DialogInterface.OnClickListener() {
			        	        public void onClick(DialogInterface dialog, int id) {
			        	           	dialog.cancel();
			        	        	Intent intent = new Intent(SenderConnection.this, Sender.class);
			        	        	intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			        	        	startActivity(intent);
			        	        }
			        	});
			        	AlertDialog alert11 = builder1.create();
			        	alert11.show();
			        }
				});	
			}
			
			protected void updateSinkStats(){
				for(int i = 0; i < sinkList.size(); ++i) {
	       			sinkList.get(i).setFileTransferComplete(false);
	       			sinkList.get(i).setFileTransferTime(0);
	       			sinkList.get(i).updateCount(0);
				}
			}
			
			void printlogs(){
				// for debugging and performance testing
				try {
					InetAddress addr = InetAddress.getByName("192.168.1.172");
			        Socket skt = new Socket(addr, 1234);
			        ObjectOutputStream out = new ObjectOutputStream(skt.getOutputStream());
			        out.writeObject(logs);
			        out.close();
			        skt.close();
			   }catch(Exception e) {  e.printStackTrace();  }
			}
			
			protected void onProgressUpdate(Integer... progress) {
				fileSentName.setText("Sending file: "+ FileName);
				currPacketNoDisp.setText("packets sent: " + Integer.toString(progress[0]) + " & packets remaining: " + Integer.toString(progress[1])+"\nTotal Clients: " + Integer.toString(progress[2]) + "\nFinished Clients: " + Integer.toString(progress[3]));
			}	
			
			protected void updateAdapter(){
				runOnUiThread(new Runnable() {
  	       		     @Override
  	       		     public void run() {
  	  	       		    	adapter.notifyDataSetChanged();
  	       		     }
  	       		});
			}
			
			public void startFileTransfer() {
				currPacketNo=0;
				countPackets = fileBuffer.size();
				while(countPackets > 0) {
					totalClients = sinkList.size();
					publishProgress(currPacketNo,countPackets,totalClients,finClients);			// update UI with current packet, lost count, finished clients, total clients
					currPacketNo += startFileTransferRound();
					countPackets = fileBuffer.size() - currPacketNo;
				}
				totalClients = sinkList.size();
				publishProgress(currPacketNo,countPackets,totalClients,finClients);
			}
			
			public int startFileTransferRound() {
				/* break transfer into rounds
				 * send EOR after each round, and receive response from a specified receiver
				 */
				DatagramPacket dataPacket;
				int numPackets = windowSize;
				numPackets = Math.min(countPackets, numPackets);
				if(currPacketNo == 0) 
					startTime = System.currentTimeMillis();
				long prev=0,curr=0;
				int i=0;
				for(i = 0; i < numPackets; ++i) {
					byte dataBuf[] = fileBuffer.get(currPacketNo + i);
		            byte[] sendBuf = createWCPacket('d', currPacketNo + i, dataBuf);
		            dataPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, sourceStartPort+sinkStart+currFileIndex);    
		            try {
		            	if(i==0){
		            		logs[logcounter++] = "InterRoundDelay " + (System.currentTimeMillis() - interRoundDelay);
		            		interRoundDelay = 0;
		            	}
		            	WCSocketFile.send(dataPacket);
		            	curr = System.currentTimeMillis()-startTime;
		            	if(i!=0)
		            		logs[logcounter++] = "Datapacketsent "+(currPacketNo + i)+" time "+(curr-prev) + " " + curr;
		            	//Log.d(ACTIVITY_SERVICE, "Data packet sent"+(currPacketNo + i)+sourceStartPort+sinkStart+currFileIndex);
		            	prev = curr;
		            } catch (IOException e) {}
		        }
				//ask for feedback from specified receiver
				interRoundDelay = System.currentTimeMillis();
				feedbackRequest(currPacketNo + i);
				//sendMetadata(); //if some client has not received META or joined late
				return numPackets;
			}
			
		    public int process(DatagramPacket packet,int lastPacket){
				MainActivity.WCPacket pkt = new MainActivity.WCPacket();
				pkt.parse(packet);
				if(pkt.type == 'b') {
	        		int checkPacket = Integer.parseInt(new String(pkt.data)) ;
	        		logs[logcounter++] = "last packet " + lastPacket+" check pkt "+checkPacket+(System.currentTimeMillis()-startTime);
	        		//Log.d(ACTIVITY_SERVICE,"EOR response received "+ checkPacket+ " "+ lastPacket);
	        		if(checkPacket == lastPacket){
		        	   	logs[logcounter++] = "eor response received" + pkt.dataStr+" "+(System.currentTimeMillis()-startTime);
		        	   	return 1;		//return 1 if eor response received
	        		}
	        	}
				else if(pkt.type == 's') {
	        		// Add SNACK packet no requested in retransmission Queue.
	        		String[] nos = new String(pkt.dataStr).split("&");
	        		logs[logcounter++] = pkt.dataStr+" "+(System.currentTimeMillis()-startTime);
	        		//Log.d(ACTIVITY_SERVICE,"Snacks "+ pkt.dataStr+" Time "+(System.currentTimeMillis()-startTime));
	        		// First no is data is number of lost packets. Update count.
	        		for(int i = 0; i < sinkList.size(); ++i) {
		       			if(sinkList.get(i).addr.getHostAddress().equals(
		       				packet.getAddress().getHostAddress()) && sinkList.get(i).getLostCount() != Integer.parseInt(nos[0])) {
		       				sinkList.get(i).updateCount(Integer.parseInt(nos[0]));
		       				updateAdapter();
		       			}
		       		}
	        		// Add SNACK packet no requested in retransmission Queue.
	        		for(int i = 1; i < nos.length; ++i) {
	        			if(retransmissionQueue.contains(new Integer(nos[i])) == false) {
	        				retransmissionQueue.add(new Integer(nos[i]));
	        			}
	        		}
	        	}
	        	else if(pkt.type == 'a') {
	        		String[] fields = new String(pkt.dataStr).split("&");
	        		// Ack recvd from Sink
	        		for(int i = 0; i < sinkList.size(); ++i) {
		       			if(sinkList.get(i).addr.getHostAddress().equals(
		       				packet.getAddress().getHostAddress()) && !sinkList.get(i).isFileTransferComplete()) {
		       				sinkList.get(i).setFileTransferComplete(true);
		       				sinkList.get(i).setFileTransferTime(Integer.parseInt(fields[0]));
		       				sinkList.get(i).updateCount(Integer.parseInt(fields[0]));
		       				Log.d(ACTIVITY_SERVICE, "ack received "+fields[0]);
		       				logs[logcounter++] = "ack received"+" "+(System.currentTimeMillis()-startTime);
		       				finClients++;
		       				totalClients = sinkList.size();
		       				publishProgress(currPacketNo,countPackets,totalClients,finClients);
		       				updateAdapter();
		       			}
	        		}
	        		boolean transferflag = true;
	        		for(int i = 0; i < sinkList.size(); ++i) {
		       			if( !sinkList.get(i).isFileTransferComplete())
		       				transferflag = false;
		       		}
	        		if(transferflag == true) {
	        			fileTransferComplete = true;
	        			return 1;
	        		}
				}
				return 0;		// 0 for snack/ack
	       	} 
			
			public void feedbackRequest(int lastPacket){
				/* send eor and wait for response
				 * meanwhile listen for pending connection requests
				 * if no eor response for 450 ms, repeat thrice before starting a new round
				 */
				
				int fbSendTries=0;
				long feedbackTimer = 0;
				long feedbackDuration = 400;
				//conn
				byte[] connBuf = new byte[256];
			    DatagramPacket connPacket = new DatagramPacket(connBuf, connBuf.length);
			    //eor
				byte[] feedBuf = createWCPacket('b', 0, Integer.toString(lastPacket));			//Ask feedback
				DatagramPacket feed = new DatagramPacket(feedBuf, feedBuf.length, WCMulticastAddress, sourceStartPort+sinkStart+currFileIndex);
				//eor response 
			    byte[] respBuf = new byte[2000];
				DatagramPacket feedback = new DatagramPacket(respBuf, respBuf.length);
			    
			    try {
			       	WCSocketSource.setSoTimeout((int) SocketTimeout); // Timeout Socket to un-block at receive()
			    } catch(SocketException e) {}
			    
			    while(fbSendTries++ < 3){
					for(int i = 0; i < 3; ++i) {
					     	try {
					       		WCSocketFile.send(feed);
					       		//Log.d(ACTIVITY_SERVICE,"EOR sent "+ lastPacket);
					       	} 	catch (IOException e) {Log.d(ACTIVITY_SERVICE, "ask feedback error");}
					}
		        	logs[logcounter++] = "eor sent " + (System.currentTimeMillis()-startTime);
		           	// now listen for pending connections
		        	while(acceptConnections(connPacket) == 1){
						// listen connections
					}
					sendMetadata();		// for new clients or the ones which lost META
		       
					try {
			        	WCSocketFile.setSoTimeout((int) SocketTimeout); // Timeout Socket to un-block at receive()
			        } catch(SocketException e) {}
					
					feedbackTimer = System.currentTimeMillis();
					while(System.currentTimeMillis() - feedbackTimer <= feedbackDuration){
						try {
							WCSocketFile.receive(feedback);
							//Log.d(ACTIVITY_SERVICE,"EOR response received incorrect ");
							if(process(feedback,lastPacket)==1){
								return;
							}
						}catch (IOException e) {}
					}
		        }
			}
			
			private void completePendingTransfer() {
				// Completing Pending Transfer to transfer file reliably in retransmission phase
				// end of file packet - asking receiver to send snacks
				byte[] eofBuf = createWCPacket('f', 0, Integer.toString(fileBuffer.size()-1).getBytes());
				DatagramPacket eof = new DatagramPacket(eofBuf, eofBuf.length, WCMulticastAddress, sourceStartPort+sinkStart+currFileIndex);    
		        for(int i = 0; i < 1; ++i) {
		        	try {
		        		WCSocketFile.send(eof);
		        	} 	catch (IOException e) {}
		        }
		        retransmissionQueue.clear();
		        while(!fileTransferComplete) {
		        	listenSNACK();
					int numRequests = retransmissionQueue.size();
					int packetNo=0;
					DatagramPacket dataPacket;
					int numPackets;
					//windowSize=50;
					numPackets=Math.min(windowSize,numRequests);
					int i;
					for(i = 0; i < numPackets; i++) {
						packetNo = retransmissionQueue.remove(0);
						byte dataBuf[] = fileBuffer.get(packetNo);
						byte[] sendBuf = createWCPacket('d', packetNo, dataBuf);
					    dataPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress,sourceStartPort+sinkStart+currFileIndex);    
					    try {
					         	WCSocketFile.send(dataPacket);
					         	isTransferCompleteTimer=System.currentTimeMillis();
					           	totalSnacks++;
					    } catch (IOException e) {}
				   }
					if(!fileTransferComplete && numPackets==windowSize)
						feedbackRequest(packetNo);
					if(isTransferComplete()){
						fileTransferComplete=showDialogBox();
						//Log.d(ACTIVITY_SERVICE, "fileTransferComplete "+ fileTransferComplete);
					}
				}
		    }
			
			protected boolean isTransferComplete(){
				// no snack/ack from receiver for 10s, decide whether to exit/start new transfer
				if(System.currentTimeMillis()- isTransferCompleteTimer >= 10000){
					isTransferCompleteTimer=System.currentTimeMillis();
					return true;
				}
				else return false;
			}
			
			boolean m_Input = false;
			protected synchronized boolean showDialogBox(){
				runOnUiThread(new Runnable(){
			        @Override
			        public void run() {
			        	AlertDialog.Builder builder1 = new AlertDialog.Builder(SenderConnection.this);
			        	builder1.setMessage("No response from "+(totalClients-finClients)+ " clients");
			        	builder1.setCancelable(true);

			        	builder1.setNeutralButton("Start Next Transfer",new DialogInterface.OnClickListener() {
			        	        public void onClick(DialogInterface dialog, int id) {
			        	        	m_Input = true;
			        	        	synchronized (SenderConnection.this) {
										SenderConnection.this.notify();
									}
			        	            dialog.cancel();
			        	        }
			        	});
			        	
			        	builder1.setPositiveButton("Exit", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int id) {
								m_Input = false;
		        	        	dialog.cancel();
		        	        	Intent intent = new Intent(SenderConnection.this, MainActivity.class);
		        	        	intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		        	        	startActivity(intent);
							}
						});

			        	builder1.setNegativeButton("Wait",new DialogInterface.OnClickListener() {
			        	        public void onClick(DialogInterface dialog, int id) {
			        	            m_Input = false;
			        	        	dialog.cancel();
			        	        	synchronized (SenderConnection.this) {
										SenderConnection.this.notify();
									}
			        	        }
			        	    });
				     	AlertDialog alert11 = builder1.create();
			        	alert11.show();
			        }
			    });	
			     try {
			    	 synchronized(SenderConnection.this) {
			    	     SenderConnection.this.wait();
			    	     //Log.d(ACTIVITY_SERVICE, "wait over");
			    	 } 
				 } catch (InterruptedException e) {
					 e.printStackTrace();
				 }
			     return m_Input;
			}
			        	
			protected void listenSNACK() {
				byte[] buf = new byte[1000];
		        DatagramPacket snack = new DatagramPacket(buf, buf.length);
		        try {
		        	WCSocketFile.setSoTimeout((int) SocketTimeout); // Timeout Socket to un-block at receive()
		        } catch(SocketException e) {}
		        long listenStartTime=System.currentTimeMillis();
		        long duration=50;   //changed from 50
		        while(System.currentTimeMillis()-listenStartTime<=duration) {
		        	try {
			       		WCSocketFile.receive(snack); 
			       		if(process(snack,0)==1)
			       			return;
			       		isTransferCompleteTimer=System.currentTimeMillis();
			        } catch(IOException e) {}	      
		    	}
		    }
		}
	

	//Main Class
	@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_sender_connection);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		Intent intent = getIntent();
		files_paths = intent.getStringArrayListExtra("files_paths");
		currPacketNoDisp = (TextView) findViewById(R.id.currPacketNo);
		fileSentName = (TextView) findViewById(R.id.file);
		fileSentName.setText("Waiting for connections ");
		currPacketNoDisp.setText("");
			
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
//			WCMulticastAddress = InetAddress.getByName(getServerMultiCastAddress(d.serverAddress));
			WCMulticastAddress = InetAddress.getByName("224.0.168.1");
			//Log.d(ACTIVITY_SERVICE,"trying to connect to..." + d.serverAddress);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		
        // open multicast connection socket 
		try {
			WCSocketSource = new MulticastSocket(WCSourcePort);
		} catch (Exception e) {System.out.println("Exception occured");}
		
		try{
			WCSocketSource.joinGroup(WCMulticastAddress);
			//Log.d(ACTIVITY_SERVICE,"Connected to..." + WCMulticastAddress.getHostAddress());
		} catch (IOException e) {
			//Log.d(ACTIVITY_SERVICE,"Some error in joining group");
		}
		sinkList = new Vector<SinkStats>();
		adapter=new SinkArrayAdapter(this, R.layout.activity_sink_list, sinkList);
		ListView listView = (ListView) findViewById(android.R.id.list);
		listView.setAdapter(adapter);
		
		button =(Button)findViewById(R.id.button1);
		button.setEnabled(false);
		
		listenThread = new Thread(new Runnable() {           
            public void run() { 
            	listenConnection();
            }});
        listenThread.start();  
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.sender_connection, menu);
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
	

	protected int acceptConnections(DatagramPacket connPacket){
		boolean exist=false;
		int isConnectionPending = 0;
		try {
	   		WCSocketSource.receive(connPacket); 
	   		MainActivity.WCPacket connP = new MainActivity.WCPacket();
	   		connP.parse(connPacket);
	       	if(connP.type == 'c') {
	       		// Add the sink to the sinkList.
	       		//logs[logcounter++] = "Connection received "+(System.currentTimeMillis()-startTime);
	       		if(!button.isEnabled()){
		       		runOnUiThread(new Runnable(){
				        @Override
				        public void run() {
				        	button.setEnabled(true);
				        }
		       		});
	       		}
	       		//send connection response
	       		InetAddress clientIP = connPacket.getAddress();
       			byte[] sendBuf = createWCPacket('r', 0, "");
    			DatagramPacket connRespPacket = new DatagramPacket(sendBuf, sendBuf.length, clientIP, WCSinkPort);  
    			//Log.d(ACTIVITY_SERVICE,"trying to connect to..." + WCMulticastAddress.getHostAddress());
    			for(int i = 0; i < 3; ++i) {
    				try {
    					WCSocketSource.send(connRespPacket);
    				} catch (IOException e) {
    					Log.d(ACTIVITY_SERVICE,"Error in sending connection response packet");
    				}
    			}
    			for(int i = 0; i < sinkList.size(); ++i) {
	       			if(sinkList.get(i).addr.getHostAddress().equals(connPacket.getAddress().getHostAddress())) 
	       				exist=true;
	       		}
	       		if(!exist){
	       			//s = new SinkStats(connP.dataStr,connPacket.getAddress());
	       			s = new SinkStats(sinkList.size()+1+" "+connP.dataStr,connPacket.getAddress());
	       			sinkList.add(s);
	       			//currPacketNoDisp.setText(sinkList.size());
	       			isConnectionPending = 1;
	       		}
	       		runOnUiThread(new Runnable() {
	       		     @Override
	       		     public void run() {
	       		    	adapter.notifyDataSetChanged();
	       		     }
	       		});
	       	}
		} catch(IOException e) { }
		return isConnectionPending;
	}
	
	public void listenConnection() {
		byte[] buf = new byte[256];
        DatagramPacket connPacket = new DatagramPacket(buf, buf.length);
        try {
        	WCSocketSource.setSoTimeout((int) SocketTimeout); // Timeout Socket to un-block at receive()
        } catch(SocketException e) {}
        while(!Thread.currentThread().isInterrupted()) {
           	acceptConnections(connPacket);
        }
    }
	
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	public void sendData(View view){
		//removing listenThread 
		listenThread.interrupt();
		synchronized(fileTransferStart ) {
			fileTransferStart = true;
		}
		totalFile = files_paths.size();
		//Log.d(ACTIVITY_SERVICE,"Total file..." + totalFile) ;
        SendDataTask DataTask = new SendDataTask();  
		DataTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
	    return;
	}
	
	private void bufferFile() {
		/* Read file into memory buffer */
		fileBuffer = new Vector<byte[]> ();
		byte[] readBuf = new byte[DatagramSize - WCHeaderSize]; // Find Data Size.
		FileInputStream inputStream = null;
		try {
			inputStream = new FileInputStream(file);
		} catch (FileNotFoundException e) {
        	System.err.println("Could not open requested file.");
        }
        int readCount; 
        try {
			while((readCount = inputStream.read(readBuf)) != -1) {
				if(readCount != DatagramSize - WCHeaderSize) {
					// Last Packet
					byte[] lastBuf = new byte[readCount];
	               	System.arraycopy(readBuf, 0, lastBuf, 0, readCount);
	               	fileBuffer.addElement(lastBuf);  
	               	break;
				}
				else {
					fileBuffer.addElement(readBuf);
					readBuf = new byte[DatagramSize - WCHeaderSize];  // Assigning new buffer for next packet.
				}
			}
		} catch (IOException e) {
			System.out.println("IOException occured");
		}
    }
	
	private void startTransfer(){
		// opening port for current transfer
				try {
					WCSocketFile = new MulticastSocket(sourceStartPort + currFileIndex);
				} catch (Exception e) {System.out.println("Exception occured");}
				try{
					WCSocketFile.joinGroup(WCMulticastAddress);
					Log.d(ACTIVITY_SERVICE,"Connected to..." + WCMulticastAddress.getHostAddress());
				} catch (IOException e) {
					//Log.d(ACTIVITY_SERVICE,"Some error in joining group");
				}
	}
	
	private void sendMetadata() {
		String metadata = FileName + "&" + Integer.toString(fileBuffer.size()) + "&/" + sinkList.get(0).addr.getHostAddress() + "&" + (totalFile-currFileIndex);
		byte[] sendBuf = createWCPacket('m', 0, metadata);
		DatagramPacket metadataPacket = new DatagramPacket(sendBuf, sendBuf.length, WCMulticastAddress, WCSinkPort);   
		for(int i = 0; i < RetryCount; ++i) {
			try {
				WCSocketFile.send(metadataPacket);
			} catch (IOException e) {}
		}
	}
	
	public String getServerMultiCastAddress (int i){
		return "224.0." +    ((i >> 8 ) & 0xFF) + "." +      ((i >> 24 ) & 0xFF ) ;
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
}