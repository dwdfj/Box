package com.github.tvbox.osc.bean;

import java.util.ArrayList;

public class SourceBean {
    private String key;
    private String name;
    private String api;
    private int type;   // 0 xml 1 json 3 Spider
    private int searchable; // 是否可搜索
    private int quickSearch; // 是否可以快速搜索
    private int filterable; // 可筛选?
    private int hide; // 设置的选择列表里隐藏
    private String playerUrl; // 站点解析Url
    private String ext; // 扩展数据
    private String jar; // 自定义jar
    private ArrayList<String> categories = null; // 分类&排序
    private int playerType; // 0 system 1 ikj 2 exo 10 mxplayer -1 以参数设置页面的为准
    private String clickSelector; // 需要点击播放的嗅探站点selector   ddrk.me;#id
    private String style; // 展示风格
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApi() {
        return api;
    }

    public void setApi(String api) {
        this.api = api;
    }

    public void setPlayerUrl(String playerUrl) {
        this.playerUrl = playerUrl;
    }

    public String getPlayerUrl() {
        return playerUrl;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public boolean isSearchable() {
        return searchable != 0;
    }

    public void setSearchable(int searchable) {
        this.searchable = searchable;
    }

    public boolean isQuickSearch() {
        return quickSearch != 0;
    }

    public void setQuickSearch(int quickSearch) {
        this.quickSearch = quickSearch;
    }

    public int getFilterable() {
        return filterable;
    }

    public void setFilterable(int filterable) {
        this.filterable = filterable;
    }

    public int getHide() {
        return hide;
    }

    public void setHide(int hide) {
        this.hide = hide;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
    }

    public ArrayList<String> getCategories() {
        // 小贾影视仓 v17: 兜底非空 —— categories 字段默认为 null, 只有在配置解析循环里
        // (ApiConfig 逐站 setCategories) 才会被赋值; 而代码中还有若干"手工 new SourceBean"的地方
        // (如参考线路注入站 __xiaojia_douban、emptyHome、搜索页的伪条目), 它们从不设置 categories。
        // 一旦该类 bean 被选为首页站点, adjustSort 里 categories.isEmpty() 会直接 NPE 闪退(切线路时高发)。
        if (categories == null) categories = new ArrayList<>();
        return categories;
    }

    public void setCategories(ArrayList<String> categories) {
        this.categories = categories;
    }

    public String getJar() {
        return jar;
    }

    public void setJar(String jar) {
        this.jar = jar;
    }

    public int getPlayerType() {
        return playerType;
    }

    public void setPlayerType(int playerType) {
        this.playerType = playerType;
    }

    public String getClickSelector() {
        return clickSelector;
    }

    public void setClickSelector(String clickSelector) {
        this.clickSelector = clickSelector;
    }

    public String getStyle() { 
        return style; 
    }

    public void setStyle(String style) { 
        this.style = style; 
    }
}
