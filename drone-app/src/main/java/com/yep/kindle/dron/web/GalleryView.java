package com.yep.kindle.dron.web;

import java.io.File;

final class GalleryView {

    private GalleryView() {}

    static String build(DeviceState deviceState) {
        AppDataManager dm = deviceState.getDataManager();
        StringBuilder items = new StringBuilder();
        if (dm != null) {
            File[] images = dm.listImageFiles();
            if (images != null) {
                for (File img : images) {
                    String name = HttpUtils.escapeHtml(img.getName());
                    items.append("<div class='item'><a href='/api/img/").append(name)
                         .append("' target='_blank'><img src='/api/img/").append(name)
                         .append("' alt='").append(name).append("'/></a>")
                         .append("<div class='name'>").append(name).append("</div></div>\n");
                }
            }
        }
        if (items.length() == 0) {
            items.append("<p style='grid-column:1/-1;text-align:center;color:#64748b;padding:40px'>No images yet</p>");
        }

        return "<!DOCTYPE html><html lang='en'><head>" +
            "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Gallery — Kindle Drone</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:system-ui,Arial,sans-serif;background:#111827;color:#e5e7eb;padding:16px}" +
            ".wrap{max-width:960px;margin:0 auto}" +
            "h1{text-align:center;font-size:20px;font-weight:700;margin-bottom:14px;color:#93c5fd}" +
            ".nav{display:flex;gap:8px;justify-content:center;margin-bottom:18px}" +
            ".nav a{padding:5px 14px;background:#1e3a5f;color:#93c5fd;text-decoration:none;border-radius:20px;font-size:13px;font-weight:600}" +
            ".nav a:hover{background:#1d4ed8}" +
            ".gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:14px}" +
            ".item{background:#1e293b;border-radius:8px;overflow:hidden}" +
            ".item a{display:block;height:150px;overflow:hidden;background:#0f172a}" +
            ".item img{width:100%;height:100%;object-fit:cover}" +
            ".item .name{padding:8px;font-size:11px;color:#64748b;text-align:center;word-break:break-all}" +
            "</style></head><body><div class='wrap'>" +
            "<h1>Image Gallery</h1>" +
            "<div class='nav'><a href='/'>Dashboard</a><a href='/gallery'>Gallery</a><a href='/books'>Books</a><a href='/logs'>Logs</a></div>" +
            "<div class='gallery'>" + items + "</div>" +
            "</div></body></html>";
    }
}
