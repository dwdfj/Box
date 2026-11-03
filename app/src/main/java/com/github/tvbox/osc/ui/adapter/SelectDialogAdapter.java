package com.github.tvbox.osc.ui.adapter;

import android.annotation.SuppressLint;
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

public class SelectDialogAdapter<T> extends ListAdapter<T, SelectDialogAdapter.SelectViewHolder> {

    private boolean muteCheck = false;

    static class SelectViewHolder extends RecyclerView.ViewHolder {

        public SelectViewHolder(@NonNull @NotNull View itemView) {
            super(itemView);
        }
    }

    public interface SelectDialogInterface<T> {
        void click(T value, int pos);

        String getDisplay(T val);
    }

    /** 小贾影视仓 v27.22: 可选的长按回调(信号源面板用它做「长按本地 py 删除」)。 */
    public interface OnItemLongClick<T> {
        boolean onLongClick(T value, int pos);
    }

    private OnItemLongClick<T> longClickListener;

    public void setOnItemLongClick(OnItemLongClick<T> listener) {
        this.longClickListener = listener;
    }


    public static DiffUtil.ItemCallback<String> stringDiff = new DiffUtil.ItemCallback<String>() {

        @Override
        public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }
    };


    private ArrayList<T> data = new ArrayList<>();

    private int select = 0;

    private SelectDialogInterface dialogInterface = null;

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback) {
        this(dialogInterface, diffCallback, false);
    }

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback, boolean muteCheck) {
        super(diffCallback);
        this.dialogInterface = dialogInterface;
        this.muteCheck = muteCheck;
    }

    public void setData(List<T> newData, int defaultSelect) {
        data.clear();
        data.addAll(newData);
        select = defaultSelect;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }


    @Override
    public SelectDialogAdapter.SelectViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new SelectViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_select, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull SelectDialogAdapter.SelectViewHolder holder, @SuppressLint("RecyclerView") int position) {
        T value = data.get(position);
        String name = dialogInterface.getDisplay(value);
        // 小贾影视仓 v27.18: 选中项打底色(shape_chip_select 的 state_selected), 不再用 "√ " 前缀 ——
        // 前缀会顶得文字左右跳, 而且隔着几行根本看不出当前选的是哪个。
        boolean checked = !muteCheck && position == select;
        TextView tv = holder.itemView.findViewById(R.id.tvName);
        holder.itemView.setSelected(checked);
        tv.setText(name);
        tv.getPaint().setFakeBoldText(checked);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!muteCheck && position == select)
                    return;
                int old = select;
                select = position;
                notifyItemChanged(old);
                notifyItemChanged(select);
                dialogInterface.click(value, position);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return longClickListener != null && longClickListener.onLongClick(value, position);
            }
        });
    }
}