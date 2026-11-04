package com.github.tvbox.osc.bean;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;

public class AbsSortJson implements Serializable {

    @SerializedName(value = "class")
    public ArrayList<AbsJsonClass> classes;

    @SerializedName(value = "list")
    public ArrayList<AbsJson.AbsJsonVod> list;

    public AbsSortXml toAbsSortXml() {
        AbsSortXml absSortXml = new AbsSortXml();
        MovieSort movieSort = new MovieSort();
        movieSort.sortList = new ArrayList<>();
        if (classes != null) {
            // v27.34: MacCMS(苹果CMS) 的分类是两层树 —— 父类(type_pid=0)本身不直接挂影片,
            // 请求 t=父类id 返回的是空 list(实测 8 个站全部 89 字节空壳)。分类栏若原样平铺,
            // 前几个 tab 全是"电视剧/电影/动漫"这类父类, 用户点第一个就空白, 观感就是"整条线路没法用"。
            // 这里把"有子类的父类"过滤掉, 只保留真正有片的子类; 没有 type_pid 字段的线路(parents 为空)行为不变。
            java.util.HashSet<String> parentIds = new java.util.HashSet<>();
            for (AbsJsonClass cls : classes) {
                if (cls == null) continue;
                String pid = cls.type_pid;
                if (pid != null && !pid.isEmpty() && !"0".equals(pid)) parentIds.add(pid);
            }
            for (AbsJsonClass cls : classes) {
                if (cls == null) continue;
                String pid = cls.type_pid;
                boolean top = (pid == null || pid.isEmpty() || "0".equals(pid));
                if (top && parentIds.contains(cls.type_id)) continue;
                MovieSort.SortData sortData = new MovieSort.SortData();
                sortData.id = cls.type_id;
                sortData.name = cls.type_name;
                sortData.flag = cls.type_flag;
                movieSort.sortList.add(sortData);
            }
        }
        if (list != null && !list.isEmpty()) {
            Movie movie = new Movie();
            ArrayList<Movie.Video> videos = new ArrayList<>();
            for (AbsJson.AbsJsonVod vod : list) {
                videos.add(vod.toXmlVideo());
            }
            movie.videoList = videos;
            absSortXml.list = movie;
        } else {
            absSortXml.list = null;
        }
        absSortXml.classes = movieSort;
        return absSortXml;
    }

    public class AbsJsonClass implements Serializable {
        public String type_id;
        public String type_name;
        public String type_flag;
        /** v27.34: MacCMS 的父分类 id, "0"/空 = 顶层分类。 */
        public String type_pid;
    }

}
