package com.pdfreader.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter for the library (folders + pdf documents). */
public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.VH> {

    public interface Callbacks {
        void onOpen(LibraryItem item);          // tap: open doc / enter folder
        void onRename(LibraryItem item);
        void onDelete(LibraryItem item);
        void onMove(LibraryItem item);
        void onOpenWith(LibraryItem item);      // external apps
    }

    private final List<LibraryItem> items = new ArrayList<>();
    private final Callbacks callbacks;
    private final LayoutInflater inflater;

    public LibraryAdapter(Context ctx, Callbacks cb) {
        this.callbacks = cb;
        this.inflater = LayoutInflater.from(ctx);
    }

    public void submit(List<LibraryItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.item_library, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LibraryItem item = items.get(position);
        holder.name.setText(item.displayName);
        boolean isFolder = item.type == LibraryItem.Type.FOLDER;
        holder.icon.setVisibility(isFolder ? View.VISIBLE : View.GONE);
        holder.thumbContainer.setVisibility(isFolder ? View.GONE : View.VISIBLE);

        if (isFolder) {
            holder.icon.setImageResource(R.drawable.ic_folder);
            holder.subtitle.setText("Folder");
            holder.thumb.setImageDrawable(null);
        } else {
            File f = new File(item.filePath);
            long pages = -1;
            String size = formatSize(f.exists() ? f.length() : item.sizeBytes);
            holder.subtitle.setText(size);
            loadThumbnail(holder, f);
        }

        holder.itemView.setOnClickListener(v -> callbacks.onOpen(item));
        holder.menu.setOnClickListener(v -> showPopupMenu(v, item));
    }

    private void loadThumbnail(VH holder, File f) {
        holder.tag = f.getAbsolutePath();
        ThumbnailLoader.load(f, bmp -> {
            if (f.getAbsolutePath().equals(holder.tag)) {
                if (bmp != null) holder.thumb.setImageBitmap(bmp);
            }
        });
    }

    private void showPopupMenu(View anchor, LibraryItem item) {
        Context ctx = anchor.getContext();
        PopupMenu menu = new PopupMenu(ctx, anchor);
        menu.getMenu().add(0, 1, 0, R.string.rename);
        menu.getMenu().add(0, 2, 1, R.string.move);
        if (item.type == LibraryItem.Type.DOCUMENT) {
            menu.getMenu().add(0, 3, 2, R.string.open_with);
        }
        menu.getMenu().add(0, 4, 3, R.string.delete);
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: callbacks.onRename(item); return true;
                case 2: callbacks.onMove(item); return true;
                case 3: callbacks.onOpenWith(item); return true;
                case 4: callbacks.onDelete(item); return true;
            }
            return false;
        });
        menu.show();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int u = 0;
        double v = bytes;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(java.util.Locale.US,
                u == 0 ? "%d %s" : "%.1f %s", (long) v, units[u]);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final FrameLayout thumbContainer;
        final ImageView thumb;
        final TextView name;
        final TextView subtitle;
        final ImageButton menu;
        Object tag;

        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.item_icon);
            thumbContainer = v.findViewById(R.id.thumb_container);
            thumb = v.findViewById(R.id.item_thumb);
            name = v.findViewById(R.id.item_name);
            subtitle = v.findViewById(R.id.item_subtitle);
            menu = v.findViewById(R.id.item_menu);
        }
    }

    /** Renders first-page thumbnails off the main thread. */
    static class ThumbnailLoader {
        interface Receiver {
            void receive(Bitmap bmp);
        }

        static void load(File f, Receiver r) {
            if (!PdfUtils.rendererSupported() || !f.exists()) {
                r.receive(null);
                return;
            }
            new AsyncTask<File, Void, Bitmap>() {
                @Override
                protected Bitmap doInBackground(File... params) {
                    ParcelFileDescriptor pfd = null;
                    try {
                        pfd = ParcelFileDescriptor.open(params[0],
                                ParcelFileDescriptor.MODE_READ_ONLY);
                        return PdfUtils.renderThumbnail(pfd, 160);
                    } catch (Exception e) {
                        return null;
                    } finally {
                        if (pfd != null) try {
                            pfd.close();
                        } catch (Exception ignored) {
                        }
                    }
                }

                @Override
                protected void onPostExecute(Bitmap bmp) {
                    r.receive(bmp);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, f);
        }
    }
}
