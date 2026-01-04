package com.threadaffinity.manager.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.threadaffinity.manager.R;
import com.threadaffinity.manager.model.AppInfo;
import java.util.ArrayList;
import java.util.List;

public class AppAdapter extends BaseAdapter {
    private Context context;
    private List<AppInfo> apps = new ArrayList<>();

    public AppAdapter(Context context) {
        this.context = context;
    }

    public void setApps(List<AppInfo> apps) {
        this.apps = apps;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return apps.size();
    }

    @Override
    public AppInfo getItem(int position) {
        return apps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_app, parent, false);
            holder = new ViewHolder();
            holder.ivIcon = convertView.findViewById(R.id.ivAppIcon);
            holder.tvAppName = convertView.findViewById(R.id.tvAppName);
            holder.tvPackageName = convertView.findViewById(R.id.tvPackageName);
            holder.tvPid = convertView.findViewById(R.id.tvPid);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        AppInfo app = apps.get(position);
        holder.ivIcon.setImageDrawable(app.getIcon());
        holder.tvAppName.setText(app.getAppName());
        holder.tvPackageName.setText(app.getPackageName());
        
        if (app.getPid() > 0) {
            holder.tvPid.setText("PID:" + app.getPid());
            holder.tvPid.setVisibility(View.VISIBLE);
        } else {
            holder.tvPid.setVisibility(View.GONE);
        }

        return convertView;
    }

    static class ViewHolder {
        ImageView ivIcon;
        TextView tvAppName;
        TextView tvPackageName;
        TextView tvPid;
    }
}
