#coding=utf-8
#!/usr/bin/python
import os
import requests
from importlib.machinery import SourceFileLoader  ### 导入这个模块
from urllib import parse
import json
import sys
# 小贾影视仓 v24.7.3: 允许写 .pyc 字节码缓存 —— 旧值 True 让每个进程启动都要完整重新编译
# 全部 py 插件(SourceFileLoader.load_module 每次都走编译), 两个 py 源时启动固定多花编译时间。
# SourceFileLoader.get_code 会自动按 mtime 校验 __pycache__ 的新鲜度, 不存在脏缓存风险。
sys.dont_write_bytecode = False

def createFile(file_path):
    if os.path.exists(file_path) is False:
        os.makedirs(file_path)

def redirectResponse(tUrl):
  # 小贾影视仓 v24.1: 补 timeout —— 原实现无超时, 慢服务器会把 py 源 init 卡到天荒地老
  rsp = requests.get(tUrl, allow_redirects=False,verify = False, timeout=15)
  if 'Location' in rsp.headers:
    return redirectResponse(rsp.headers['Location'])
  else:
    return rsp

def downloadFile(name,url):
    try:
        rsp = redirectResponse(url)
        if rsp.status_code >= 400:
            return False
        # 小贾影视仓 v24.1: 先写 .tmp 再原子替换 —— 原实现直接 open('wb') 会先截断旧文件,
        # 回源一旦失败连本地缓存一起丢; 现在失败时旧文件原封不动, py 源仍可用。
        tmp = name + '.tmp'
        with open(tmp,'wb') as f:
            f.write(rsp.content)
        os.replace(tmp, name)
        print(url)
        return True
    except:
        print(name + ' =======================================> error')
        print(url)
        return False

def downloadPlugin(basePath,url):
    createFile(basePath)
    name = url.split('/')[-1].split('.')[0]
    pyName = ''
    if url.startswith('file://'):
        pyName = url.replace('file://','')
    else:
        pyName = basePath + name+'.py'
        # 小贾影视仓 v24.1: 插件本地缓存 12 小时 —— 原实现每次 init 都重新联网下载,
        # 切线路/重启/加载依赖时 py 源都要白等一次网络(慢网数秒~十几秒), 这是「py 源加载慢」的最大根因。
        # 缓存 12h 内直接用本地; 过期才回源; 回源失败且本地有旧文件时沿用旧文件(不再让 init 直接失败)。
        import time as _time
        fresh = False
        try:
            if os.path.exists(pyName) and os.path.getsize(pyName) > 0 \
                    and (_time.time() - os.path.getmtime(pyName)) < 12 * 3600:
                fresh = True
        except Exception:
            pass
        if not fresh:
            downloadFile(pyName, url)
    sPath = gParam['SpiderPath']
    sPath[name] = pyName
    sParam = gParam['SpiderParam']
    paramList = parse.parse_qs(parse.urlparse(url).query).get('extend')
    if paramList == None:
        paramList = ['']
    sParam[name] = paramList[0]
    return pyName

def loadFromDisk(fileName):
    name = fileName.split('/')[-1].split('.')[0]
    spList = gParam['SpiderList']
    if name not in spList:
        sp = SourceFileLoader(name, fileName).load_module().Spider()
        spList[name] = sp
    return spList[name]

def str2json(content):
    return json.loads(content)

gParam = {
    "SpiderList":{},
    "SpiderPath":{},
    "SpiderParam":{}
}

def getDependence(ru):
    result = ru.getDependence()
    return result

def getName(ru):
    result = ru.getName()
    return result

def init(ru,extend):
    spoList = []
    spList = gParam['SpiderList']
    sPath = gParam['SpiderPath']
    sParam = gParam['SpiderParam']
    for key in ru.getDependence():
        sp = None
        if key in spList.keys():
            sp = spList[key]
        elif key in sPath.keys():
            sp = loadFromDisk(sPath[key])
        if sp != None:
            # 小贾影视仓 v23.8: 防御式注入 —— 第三方 py 源可能没有 setExtendInfo 方法,
            # 或 sParam 里缺这个 key, 旧版直接抛异常导致整个源 init 失败
            try:
                sp.setExtendInfo(sParam.get(key, ''))
            except Exception:
                pass
            spoList.append(sp)
    ru.init(extend)

def homeContent(ru,filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def categoryContent(ru,tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def detailContent(ru,array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def playerContent(ru,flag,id,vipFlags):
    result = ru.playerContent(flag,id,str2json(vipFlags))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def liveContent(ru,url):
    result = ru.liveContent(url)
    return result

def searchContent(ru,key,quick,pg="1"):
    # 小贾影视仓 v23.8: 透传 pg(默认第 1 页), 兼容三参签名的第三方 py 源
    result = ru.searchContent(key,quick,pg)
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def localProxy(ru,param):
    result = ru.localProxy(str2json(param))
    return result

def run():
    pass

if __name__ == '__main__':
    run()
