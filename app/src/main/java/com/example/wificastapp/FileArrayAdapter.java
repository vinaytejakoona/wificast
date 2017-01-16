package com.example.wificastapp;
import java.util.List; 
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.iitb.wifimulticast.R;

public class FileArrayAdapter extends ArrayAdapter<Item>{

        private Context c;
        private int id;
        private List<Item>items;
       
        public FileArrayAdapter(Context context, int textViewResourceId,
                        List<Item> objects) {
                super(context, textViewResourceId, objects);
                c = context;
                id = textViewResourceId;
                items = objects;
        }
        public Item getItem(int i)
         {
                 return items.get(i);
         }
         @Override
       public View getView(int position, View convertView, ViewGroup parent) {
               View v = convertView;
               if (v == null) {
                   LayoutInflater vi = (LayoutInflater)c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                   v = vi.inflate(id, null);
               }
               final Item o = items.get(position);
               if (o != null) {
                       TextView t1 = (TextView) v.findViewById(R.id.TextView01);
                       TextView t2 = (TextView) v.findViewById(R.id.TextView02);
                       TextView t3 = (TextView) v.findViewById(R.id.TextViewDate);
                       /* Take the ImageView from layout and set the city's image */
                       ImageView imageCity = (ImageView) v.findViewById(R.id.fd_Icon1);
                       String uri = "drawable/" + o.getImage();
//                       int imageResource = R.drawable.ic_launcher;
                       int imageResource = c.getResources().getIdentifier(uri, null, c.getPackageName());
//                       Drawable image = c.getResources().getDrawable(imageResource);
//                       imageCity.setImageDrawable(image);
                      //TODO put direcotry_icon and file_icon image in drawable
                       //Src: http://custom-android-dn.blogspot.in/2013/01/create-simple-file-explore-in-android.html
                       if(t1!=null)
                    	   t1.setText(o.getName());
                       if(t2!=null)
                           t2.setText(o.getData());
                       if(t3!=null)
                            t3.setText(o.getDate());
               }
               return v;
       }
}