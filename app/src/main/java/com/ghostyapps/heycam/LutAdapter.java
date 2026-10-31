package com.ghostyapps.heycam;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;

public class LutAdapter extends RecyclerView.Adapter<LutAdapter.LutViewHolder> {

    private List<File> lutFiles;
    private String selectedLutName;
    private final OnLutActionListener listener;

    public interface OnLutActionListener {
        void onLutSelected(File file); // null gelirse "No LUT" demektir
        void onLutDeleted(File file);
        void onLutRename(File file);
    }

    public LutAdapter(List<File> lutFiles, String selectedLutName, OnLutActionListener listener) {
        this.lutFiles = lutFiles;
        this.selectedLutName = selectedLutName;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LutViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lut, parent, false);
        return new LutViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LutViewHolder holder, int position) {

        // --- POZİSYON 0: "NO LUT" SEÇENEĞİ ---
        holder.divider.setVisibility(position == 0 ? View.GONE : View.VISIBLE);

        if (position == 0) {
            holder.txtName.setText("None");

            // "No LUT" seçeneği silinemez, çöp kutusunu gizle
            holder.btnDelete.setVisibility(View.GONE);

            // Uzun basmayı devre dışı bırak
            holder.itemView.setOnLongClickListener(null);

            // Eğer kayıtlı isim boşsa veya null ise, "No LUT" seçilidir
            boolean isNoLutSelected = (selectedLutName == null || selectedLutName.isEmpty());

            bindSelected(holder, isNoLutSelected);

            holder.itemView.setOnClickListener(v -> {
                selectedLutName = ""; // Seçimi temizle
                notifyDataSetChanged();
                listener.onLutSelected(null); // Activity'e null gönder
            });

            return; // 0. pozisyon bitti, aşağıya inme
        }

        // --- POZİSYON 1 ve SONRASI: GERÇEK DOSYALAR ---
        // Listeden veri çekerken (position - 1) yapıyoruz çünkü 0. sırayı "No LUT" kaptı
        File file = lutFiles.get(position - 1);

        holder.txtName.setText(file.getName().replace(".cube", ""));
        holder.btnDelete.setVisibility(View.VISIBLE); // Dosyalar silinebilir

        boolean isSelected = file.getName().equals(selectedLutName);
        bindSelected(holder, isSelected);

        holder.itemView.setOnClickListener(v -> {
            selectedLutName = file.getName();
            notifyDataSetChanged();
            listener.onLutSelected(file);
        });

        // --- YENİ EKLENEN: UZUN BASMA (RENAME) ---
        holder.itemView.setOnLongClickListener(v -> {
            listener.onLutRename(file);
            return true; // Olayı tükettik (Click çalışmasın)
        });

        holder.btnDelete.setOnClickListener(v -> listener.onLutDeleted(file));
    }

    // Selected row: red underline under the name, regular instead of light weight.
    private void bindSelected(LutViewHolder holder, boolean selected) {
        holder.selectedLine.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.txtName.setTypeface(ResourcesCompat.getFont(holder.itemView.getContext(),
                selected ? R.font.barlow_regular : R.font.barlow_light));
    }

    @Override
    public int getItemCount() {
        // Dosya sayısı + 1 (No LUT seçeneği için)
        if (lutFiles == null) return 1;
        return lutFiles.size() + 1;
    }

    static class LutViewHolder extends RecyclerView.ViewHolder {
        TextView txtName;
        TextView btnDelete;
        View selectedLine;
        View divider;

        public LutViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txt_lut_name);
            btnDelete = itemView.findViewById(R.id.btn_delete_lut);
            selectedLine = itemView.findViewById(R.id.view_selected_line);
            divider = itemView.findViewById(R.id.view_divider);
        }
    }
}