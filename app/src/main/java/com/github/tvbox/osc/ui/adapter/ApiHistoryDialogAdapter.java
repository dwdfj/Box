package com.github.tvbox.osc.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ApiHistoryDialogAdapter extends ListAdapter<String, ApiHistoryDialogAdapter.SelectViewHolder> {

    class SelectViewHolder extends RecyclerView.ViewHolder {

        public SelectViewHolder(@NonNull @NotNull View itemView) {
            super(itemView);
        }
    }

    public interface SelectDialogInterface {
        void click(String value);

        void del(String value, ArrayList<String> data);
    }


    private ArrayList<String> data = new ArrayList<>();

    private String select = "";

    private SelectDialogInterface dialogInterface = null;

    public ApiHistoryDialogAdapter(SelectDialogInterface dialogInterface) {
        super(new DiffUtil.ItemCallback<String>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                return oldItem.equals(newItem);
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                return oldItem.equals(newItem);
            }
        });
        this.dialogInterface = dialogInterface;
    }

    public void setData(List<String> newData, int defaultSelect) {
        data.clear();
        data.addAll(newData);
        select = data.get(defaultSelect);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }


    @Override
    public ApiHistoryDialogAdapter.SelectViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new ApiHistoryDialogAdapter.SelectViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_api_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull ApiHistoryDialogAdapter.SelectViewHolder holder, int position) {
        String value = data.get(position);
        String name = value;
        if (select.equals(value))
            name = "√ " + name;
        ((TextView) holder.itemView.findViewById(R.id.tvName)).setText(name);
        holder.itemView.findViewById(R.id.tvName).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (select.equals(value))
                    return;
                notifyItemChanged(data.indexOf(select));
                select = value;
                notifyItemChanged(data.indexOf(value));
                dialogInterface.click(value);
            }
        });
        holder.itemView.findViewById(R.id.tvDel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 小贾影视仓: 允许删除当前选中的项(含最后一条), 删除后选中态自动切换
                if (select.equals(value)) {
                    int idx = data.indexOf(value);
                    if (data.size() > 1) {
                        select = data.get(idx == 0 ? 1 : 0);
                    } else {
                        select = "";
                    }
                }
                data.remove(value);
                notifyDataSetChanged();
                dialogInterface.del(value, data);
            }
        });
    }
}
