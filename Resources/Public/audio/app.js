/* OrzMusic library UI — intentionally dependency-free apart from Alpine. */
const ORZ_COLORS={modules:'#8be9fd',retro:'#fbbf24',synth:'#39e58c',standard:'#a1a1aa',other:'#94a3b8'};
const ORZ_FORMATS = [
    ...(globalThis.ORZ_DECODER_FORMATS||[]).map(item=>({...item,color:ORZ_COLORS[item.group]||ORZ_COLORS.other})),
    ...[['mp3','MP3'],['ogg','OGG'],['flac','FLAC'],['wav','WAV'],['m4a','M4A'],['aac','AAC']].map(([id,label])=>({id,label,group:'standard',color:ORZ_COLORS.standard}))
];
// Keep browser preflight aligned with the server's AudioFormat cases. The
// generated decoder manifest currently contains `thx`, which the server does
// not accept; the server remains the authoritative validation boundary.
const ORZ_IMPORT_EXTENSIONS=new Set(ORZ_FORMATS.map(item=>item.id).filter(id=>id!=='thx'));
const ORZ_MAX_UPLOAD_BYTES=32*1024*1024;
const ORZ_GROUPS=[['modules','模块音乐'],['retro','复古主机'],['synth','芯片与合成'],['standard','常规音频'],['other','其他格式']];
const clamp=(value,min=0,max=1)=>Math.min(max,Math.max(min,Number(value)||0));
const formatClock=seconds=>{if(!Number.isFinite(Number(seconds))||Number(seconds)<0)return '0:00';const n=Math.floor(Number(seconds)),h=Math.floor(n/3600),m=Math.floor(n%3600/60),s=String(n%60).padStart(2,'0');return h?`${h}:${String(m).padStart(2,'0')}:${s}`:`${m}:${s}`};
const formatDuration=seconds=>Number.isFinite(Number(seconds))&&Number(seconds)>0?formatClock(seconds):'—';
// fetch has no upload progress events; XMLHttpRequest.upload.onprogress is the
// only standard way to surface bytes during a multipart upload.
const uploadWithProgress=(formData,headers,onProgress)=>new Promise((resolve,reject)=>{
    const xhr=new XMLHttpRequest();
    xhr.open('POST','/api/upload');
    if(headers?.Authorization)xhr.setRequestHeader('Authorization',headers.Authorization);
    xhr.timeout=5*60*1000;
    xhr.upload.onprogress=event=>{if(event.lengthComputable)onProgress?.({loaded:event.loaded,total:event.total})};
    xhr.onload=()=>{let body=null;try{body=JSON.parse(xhr.responseText||'null')}catch(e){body=null}resolve({status:xhr.status,body})};
    xhr.onerror=()=>reject(new Error('网络错误，请重试'));
    xhr.ontimeout=()=>reject(new Error('上传超时，请重试'));
    xhr.send(formData);
});
const isEditableTarget=target=>Boolean(target?.closest?.('input,textarea,select,[contenteditable="true"]'));
const releaseShortcutFocus=()=>{const active=document.activeElement;if(active&&active!==document.body&&active.matches?.('button,[tabindex]'))active.blur()};
const shortcutAction=(event,editable=isEditableTarget(event.target))=>{
    const key=event.key?.toLowerCase();
    if((event.metaKey||event.ctrlKey)&&key==='k') return 'search';
    if(editable) return key==='escape'?'escape':null;
    return ({' ':'play','arrowleft':event.shiftKey?'back15':'back5','arrowright':event.shiftKey?'forward15':'forward5','arrowup':'volumeUp','arrowdown':'volumeDown','m':'mute','n':'next','p':'prev','l':'locate','v':event.shiftKey?'visualizerMode':'visualizer','/':'search','q':'queue','i':'import','?':'help','h':'help','escape':'escape'})[key]||null;
};

let player=null;
function playerApp(){return{
    songs:[],page:1,perPage:50,hasMore:false,isLoading:false,totalResults:0,searchQuery:'',formatFilter:'',formatCounts:{},libraryTotal:0,
    currentSong:null,selectedSong:null,queue:[],queueIndex:-1,isPlaying:false,isLoadingTrack:false,volume:.7,lastVolume:.7,progressPercent:0,currentTime:0,duration:0,seekPreview:null,
    sidebarOpen:false,playlistOpen:false,shortcutOpen:false,importOpen:false,adminEnabled:false,adminToken:'',importItems:[],importRunning:false,_importPromise:null,playlists:[],newPlaylistName:'',toasts:[],toastId:0,_playlistsLoaded:false,_playlistsRequest:null,
    locating:false,locatedSongId:null,visualizerOpen:true,visualizerMode:'holographic',_visualizer:null,_visualizerInited:false,
    _shortcuts:[{key:'Space',label:'播放 / 暂停'},{key:'← / →',label:'前后 5 秒'},{key:'Shift + ← / →',label:'前后 15 秒'},{key:'↑ / ↓',label:'调整音量'},{key:'M',label:'静音'},{key:'P / N',label:'上一首 / 下一首'},{key:'L',label:'定位当前曲目'},{key:'V',label:'展开 / 收起声场'},{key:'Shift + V',label:'切换声场类型'},{key:'⌘K 或 /',label:'搜索'},{key:'Q',label:'播放队列'},{key:'I',label:'导入本地目录'},{key:'? 或 H',label:'显示快捷键帮助'},{key:'Esc',label:'关闭面板 / 清空搜索'}],
    get shortcuts(){return this._shortcuts.filter(item=>this.adminEnabled||item.key!=='I')},
    async init(){
        player=new OrzAudioPlayer(); player.volume=this.volume; this.attachPlayerCallbacks(); player.initWasm();
        this.adminToken=this.readAdminToken();
        this.refreshAdminStatus();
        await Promise.all([this.loadFormatCounts(),this.loadSongs()]);
        window.addEventListener('scroll',()=>this.onScroll(),{passive:true});
    },
    attachPlayerCallbacks(){
        player.onTimeUpdate=(ct,dur)=>{this.currentTime=ct;this.duration=dur;this.isPlaying=player.isPlaying;this.progressPercent=dur>0?clamp(ct/dur)*100:0;if(dur>0&&this.currentSong&&!this.currentSong.duration)this.currentSong.duration=dur};
        player.onEnded=()=>this.next(); player.onNext=()=>this.next(); player.onPrev=()=>this.prev();
        player.onPlaybackStateChange=value=>{this.isPlaying=value;this.isLoadingTrack=false;this._syncVisualizer()};
        player.onError=error=>{this.isLoadingTrack=false;this.notify(`无法播放：${error?.message||'未知错误'}`,'error')};
        if(globalThis.ORZ_PLAYBACK_DIAGNOSTICS_ENABLED===true)player.onDiagnostic=payload=>{void fetch('/api/diagnostics/playback',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload),keepalive:true}).catch(()=>{})};
    },
    _initVisualizer(){
        if(!globalThis.OrzAudioVisualizer)return;const canvas=document.getElementById('visualizerCanvas');if(!canvas||this._visualizer)return;try{const rm=window.matchMedia('(prefers-reduced-motion:reduce)').matches;this._visualizer=new OrzAudioVisualizer({canvas,analyser:player?.getAnalyser()||null,reducedMotion:rm});this._visualizerInited=true}catch(e){console.warn('Visualizer init failed:',e);this._visualizer=null}},
    _syncVisualizer(){
        if(!this._visualizer){if(this._visualizerInited)return;if(this.currentSong&&this.visualizerOpen&&!this._visualizerInited){this._initVisualizer();if(!this._visualizer)return}else return}
        this._visualizer.setAnalyser(player?.getAnalyser()||null);
        if(this.isPlaying&&this.visualizerOpen)this._visualizer.start();
        else if(this.currentSong&&this.visualizerOpen)this._visualizer.pause();
        else this._visualizer.stop()
    },
    toggleVisualizerMode(){
        const next=this.visualizerMode==='holographic'?'spectrum':'holographic';this.visualizerMode=next;if(this._visualizer)this._visualizer.setMode(next)
    },
    toggleVisualizer(){if(!this.currentSong)return;this.visualizerOpen=!this.visualizerOpen;this._syncVisualizer()},
    get formatGroups(){
        const known=new Set(ORZ_FORMATS.map(x=>x.id)); const extras=Object.keys(this.formatCounts).filter(x=>!known.has(x)).map(id=>({id,label:id.toUpperCase(),group:'other',color:'#9ca3af'}));
        const all=[...ORZ_FORMATS,...extras].filter(x=>(this.formatCounts[x.id]||0)>0).map(x=>({...x,count:this.formatCounts[x.id]}));
        return ORZ_GROUPS.map(([id,label])=>({id,label,items:all.filter(x=>x.group===id)}));
    },
    get activeFormatLabel(){return this.formatFilter?(ORZ_FORMATS.find(x=>x.id===this.formatFilter)?.label||this.formatFilter.toUpperCase()):'全部曲目'},
    get summaryText(){if(this.isLoading&&!this.songs.length)return '正在整理音乐资料库';return this.searchQuery?`“${this.searchQuery}” · ${this.totalResults} 首结果`:`${this.totalResults} 首曲目`},
    get timeDisplay(){return `${this.formatTime(this.currentTime)} / ${this.formatTime(this.duration)}`},
    get nowPlayingMeta(){if(!this.currentSong)return '支持 Native · WASM · Server 解码';const artist=this.currentSong.artist?.name||'未知艺术家';return `${artist} · ${this.currentSong.fileFormat.toUpperCase()} · ${this.strategyLabel(this.currentSong.playStrategy)}`},
    get activeList(){return this.songs.some(s=>s.id===this.currentSong?.id)?this.songs:this.queue},
    get currentIndex(){return this.activeList.findIndex(s=>s.id===this.currentSong?.id)},
    get canPrev(){return this.currentIndex>0},get canNext(){return this.currentIndex>=0&&this.currentIndex<this.activeList.length-1},
    get importCreated(){return this.importItems.filter(item=>item.status==='created').length},
    get importDuplicates(){return this.importItems.filter(item=>item.status==='duplicate').length},
    get importFailed(){return this.importItems.filter(item=>item.status==='failed').length},
    // Preflight-rejected items (status failed && !retryable) never upload, so
    // they are excluded from both the byte numerator and denominator.
    importIsCounted(item){return !(item.status==='failed'&&!item.retryable)},
    get importTotalBytes(){return this.importItems.reduce((sum,item)=>this.importIsCounted(item)?sum+(item.file?.size||0):sum,0)},
    get importLoadedBytes(){return this.importItems.reduce((sum,item)=>{if(!this.importIsCounted(item)||item.status==='queued')return sum;if(item.status==='uploading')return sum+(item.loaded||0);return sum+(item.file?.size||0)},0)},
    get importProgress(){return this.importTotalBytes?Math.round(this.importLoadedBytes/this.importTotalBytes*100):0},
    itemUploadPercent(item){const total=item?.uploadTotal||item?.file?.size||1;return Math.min(100,Math.round((item?.loaded||0)/total*100))},
    currentUploadLabel(){const item=this.importItems.find(i=>i.status==='uploading');return item?`${item.path} · ${this.itemUploadPercent(item)}%`:'准备中'},
    strategyLabel(value){return({directFile:'浏览器直放',wasmDecode:'WASM 实时解码',serverDecode:'服务端解码'})[value]||'自动解码'},
    formatColor(format){return ORZ_FORMATS.find(x=>x.id===format?.toLowerCase())?.color||'#9ca3af'},
    formatTime(seconds){return formatClock(seconds)},formatDuration(seconds){return formatDuration(seconds)},
    formatBytes(bytes){const n=Number(bytes)||0;if(!n)return '—';const units=['B','KB','MB','GB'];const i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),3);return `${(n/1024**i).toFixed(i?1:0)} ${units[i]}`},
    async loadFormatCounts(){try{const res=await fetch('/api/songs/formats');if(!res.ok)throw new Error(`HTTP ${res.status}`);const data=await res.json();this.libraryTotal=Number(data.total)||0;this.formatCounts=Object.fromEntries((data.formats||[]).map(x=>[String(x.format).toLowerCase(),Number(x.count)||0]));return true}catch(error){this.mergeVisibleFormatCounts();this.notify('格式统计加载失败，已显示当前列表中的格式','error');return false}},
    mergeVisibleFormatCounts(){const visible={...this.formatCounts};for(const song of this.songs){const format=String(song.fileFormat||'').toLowerCase();if(format&&!visible[format])visible[format]=this.songs.filter(item=>String(item.fileFormat||'').toLowerCase()===format).length}this.formatCounts=visible;if(!this.libraryTotal)this.libraryTotal=this.totalResults||this.songs.length},
    songURL(page=this.page){const query=new URLSearchParams({page:String(page),per:String(this.perPage)});if(this.formatFilter)query.set('format',this.formatFilter);return `/api/songs?${query}`},
    async loadSongs(){this.isLoading=true;this.page=1;try{const res=await fetch(this.songURL());if(!res.ok)throw new Error(`HTTP ${res.status}`);const data=await res.json();this.songs=data.items||[];this.totalResults=data.metadata?.total||0;this.hasMore=(data.metadata?.page*data.metadata?.per)<this.totalResults;this.mergeVisibleFormatCounts()}catch(error){this.songs=[];this.notify('曲目列表加载失败','error')}finally{this.isLoading=false}},
    async loadMore(){if(this.isLoading||!this.hasMore||this.searchQuery)return;this.isLoading=true;try{const res=await fetch(this.songURL(++this.page));if(!res.ok)throw new Error(`HTTP ${res.status}`);const data=await res.json();this.songs=[...this.songs,...(data.items||[])];this.hasMore=(data.metadata?.page*data.metadata?.per)<(data.metadata?.total||0)}catch(error){this.page--;this.notify('加载更多曲目失败','error')}finally{this.isLoading=false}},
    async loadSongPage(page){this.isLoading=true;this.page=page;this.songs=[];try{const res=await fetch(this.songURL(page));if(!res.ok)throw new Error(`HTTP ${res.status}`);const data=await res.json();this.songs=data.items||[];this.totalResults=data.metadata?.total||0;this.hasMore=(data.metadata?.page*data.metadata?.per)<this.totalResults}catch(error){throw error}finally{this.isLoading=false}},
    scrollToSong(songId){const row=document.querySelector(`[data-song-id="${songId}"]`);if(!row)return false;row.scrollIntoView({behavior:'smooth',block:'center'});row.focus?.({preventScroll:true});this.locatedSongId=songId;setTimeout(()=>{if(this.locatedSongId===songId)this.locatedSongId=null},1200);return true},
    async locateCurrentSong(){if(!this.currentSong||this.locating)return false;this.locating=true;this.locatedSongId=null;const songId=this.currentSong.id;let snapshot=null;try{if(this.scrollToSong(songId))return true;snapshot={searchQuery:this.searchQuery,formatFilter:this.formatFilter,page:this.page,songs:[...this.songs],totalResults:this.totalResults,hasMore:this.hasMore};this.searchQuery='';this.formatFilter='';const res=await fetch(`/api/songs/${songId}/location?per=${this.perPage}`);if(!res.ok)throw new Error(`HTTP ${res.status}`);const loc=await res.json();await this.loadSongPage(loc.page);await this.$nextTick();if(!this.scrollToSong(songId))throw new Error('Song row missing from located page');return true}catch(error){if(snapshot){this.searchQuery=snapshot.searchQuery;this.formatFilter=snapshot.formatFilter;this.page=snapshot.page;this.songs=snapshot.songs;this.totalResults=snapshot.totalResults;this.hasMore=snapshot.hasMore}this.notify('无法定位当前曲目','error');return false}finally{this.locating=false}},
    onScroll(){if(document.documentElement.scrollHeight-window.scrollY-window.innerHeight<240)this.loadMore()},
    async search(){const query=this.searchQuery.trim();if(!query)return this.loadSongs();this.isLoading=true;try{const params=new URLSearchParams({q:query});if(this.formatFilter)params.set('format',this.formatFilter);const res=await fetch(`/api/songs/search?${params}`);if(!res.ok)throw new Error(`HTTP ${res.status}`);this.songs=await res.json();this.totalResults=this.songs.length;this.hasMore=false}catch(error){this.notify('搜索失败','error')}finally{this.isLoading=false}},
    async selectFormat(format){this.formatFilter=format;this.sidebarOpen=false;this.selectedSong=null;if(this.searchQuery.trim())await this.search();else await this.loadSongs()},
    clearSearch(){if(this.searchQuery){this.searchQuery='';this.loadSongs()}else document.querySelector('#songSearch')?.blur()},resetFilters(){this.searchQuery='';this.selectFormat('')},
    readAdminToken(){try{return sessionStorage.getItem('orz-admin-api-token')||''}catch(error){return ''}},
    saveAdminToken(){try{const token=this.adminToken.trim();if(token)sessionStorage.setItem('orz-admin-api-token',token);else sessionStorage.removeItem('orz-admin-api-token')}catch(error){this.notify('此浏览器无法保存本次会话令牌','error')}},
    clearAdminToken(){this.adminToken='';this.saveAdminToken()},
    async refreshAdminStatus(){try{const res=await fetch('/api/health');if(!res.ok)throw new Error(`HTTP ${res.status}`);const data=await res.json();this.adminEnabled=data.adminApi==='enabled'}catch(error){this.adminEnabled=false}},
    openImportPanel(){this.importOpen=true},
    isImportFileSupported(file){const name=String(file?.name||'');const dot=name.lastIndexOf('.');return dot>0&&ORZ_IMPORT_EXTENSIONS.has(name.slice(dot+1).toLowerCase())},
    importPathFor(file){return file?.webkitRelativePath||file?.name||'未命名文件'},
    async selectImportFiles(files){
        if(this.importRunning)return false;
        const selected=Array.from(files||[]);if(!selected.length)return false;
        this.importItems=selected.map(file=>{
            const problem=!this.isImportFileSupported(file)?'不支持的音频格式':file.size>ORZ_MAX_UPLOAD_BYTES?'文件超过 32 MiB 限制':null;
            return {file,path:this.importPathFor(file),status:problem?'failed':'queued',error:problem,retryable:!problem,loaded:0};
        });
        const rejected=this.importFailed;if(rejected)this.notify(`${rejected} 个文件未通过导入预检`,'error');
        return this.startImport();
    },
    async startImport(){
        if(this.importRunning)return this._importPromise||false;
        if(!this.adminToken.trim()){
            // Without a token every queued item would sit in "等待中" forever.
            // Mark them retryable-failed instead so the list shows a reason and
            // the user can start once a token is entered.
            this.notify('请先填写管理令牌，再点击“重试失败项”开始导入','error');
            for(const item of this.importItems){
                if(item.status==='queued'){item.status='failed';item.error='未设置管理令牌，请填写后重试';item.retryable=true}
            }
            return false;
        }
        if(!this.importItems.some(item=>item.status==='queued'))return true;
        this.importRunning=true;
        this._importPromise=(async()=>{
            const workers=Array.from({length:Math.min(2,this.importItems.filter(item=>item.status==='queued').length)},()=>this.runImportWorker());
            await Promise.all(workers);this.importRunning=false;this._importPromise=null;
            await Promise.all([this.loadFormatCounts(),this.loadSongs()]);
            if(this.importFailed)this.notify(`目录导入完成：${this.importCreated} 个新增，${this.importDuplicates} 个重复，${this.importFailed} 个失败`,this.importFailed?'error':'success');
            else this.notify(`目录导入完成：${this.importCreated} 个新增，${this.importDuplicates} 个重复`);
            return true;
        })();
        return this._importPromise;
    },
    async runImportWorker(){while(true){const item=this.importItems.find(candidate=>candidate.status==='queued');if(!item)return;await this.uploadImportItem(item)}},
    async uploadImportItem(item){
        item.status='uploading';item.error='';item.loaded=0;
        try{
            const body=new FormData();body.append('file',item.file,item.file.name);body.append('relativePath',item.path);
            const {status,body:payload}=await uploadWithProgress(body,{Authorization:`Bearer ${this.adminToken.trim()}`},progress=>{item.loaded=progress.loaded;item.uploadTotal=progress.total||item.file.size});
            if(status<200||status>=300){
            const reason=payload?.error==='admin_api_disabled'
                ?'服务端未启用管理 API（需配置 ADMIN_API_TOKEN）'
                :payload?.error==='unauthorized'
                    ?'管理令牌不正确'
                    :(payload?.reason||payload?.error||`HTTP ${status}`);
            throw new Error(reason);
        }
            item.status=payload?.status==='duplicate'||status===200?'duplicate':'created';item.retryable=false;
        }catch(error){item.status='failed';item.error=error?.message||'上传失败';item.retryable=true}
    },
    retryImportFailures(){if(this.importRunning)return;const failures=this.importItems.filter(item=>item.status==='failed'&&item.retryable);for(const item of failures){item.status='queued';item.error=''}if(failures.length)return this.startImport();return false},
    async playSong(song){if(!player)return;const request=(this.playRequestGen=(this.playRequestGen||0)+1);this.currentSong=song;this.selectedSong=song;this.isPlaying=false;this.isLoadingTrack=true;this.currentTime=0;this.duration=song.duration||0;this.progressPercent=0;await this.$nextTick();this._syncVisualizer();await player.play(song);if(request!==this.playRequestGen||player.currentSong?.id!==song.id)return;this.isLoadingTrack=false;this.isPlaying=player.isPlaying;this._syncVisualizer();if(!this.queue.some(s=>s.id===song.id))this.queue.push(song);this.queueIndex=this.queue.findIndex(s=>s.id===song.id)},
    prewarmSong(song){if(!player||song?.playStrategy!=='wasmDecode')return;const run=()=>{void player.prewarmWasm(song.fileFormat)};if(typeof requestIdleCallback==='function')requestIdleCallback(run,{timeout:750});else setTimeout(run,0)},
    async togglePlay(){if(player&&this.currentSong)this.isPlaying=await player.togglePlay()},
    prev(){if(this.canPrev)this.playSong(this.activeList[this.currentIndex-1])},next(){if(this.canNext)this.playSong(this.activeList[this.currentIndex+1])},
    seek(event){if(!this.currentSong)return;const rect=event.currentTarget.getBoundingClientRect(),pct=clamp((event.clientX-rect.left)/rect.width);this.seekToPercent(pct)},
    seekToPercent(pct){pct=clamp(pct);if(player?.seek(pct)){this.currentTime=pct*this.duration;this.progressPercent=pct*100;return true}return false},
    previewSeek(event){if(!this.duration)return;const rect=event.currentTarget.getBoundingClientRect(),left=clamp(event.clientX-rect.left,0,rect.width);this.seekPreview={left,time:left/rect.width*this.duration}},
    setVolume(){this.volume=clamp(this.volume);if(this.volume>0)this.lastVolume=this.volume;player?.setVolume(this.volume)},
    adjustVolume(delta){this.volume=clamp(this.volume+delta);this.setVolume()},toggleMute(){if(this.volume>0){this.lastVolume=this.volume;this.volume=0}else this.volume=this.lastVolume||.7;this.setVolume()},
    seekRelative(seconds){if(!this.duration)return;this.seekToPercent((this.currentTime+seconds)/this.duration)},
    handleShortcut(event){const action=shortcutAction(event);if(!action)return;event.preventDefault();releaseShortcutFocus();({play:()=>this.togglePlay(),back5:()=>this.seekRelative(-5),forward5:()=>this.seekRelative(5),back15:()=>this.seekRelative(-15),forward15:()=>this.seekRelative(15),volumeUp:()=>this.adjustVolume(.05),volumeDown:()=>this.adjustVolume(-.05),mute:()=>this.toggleMute(),next:()=>this.next(),prev:()=>this.prev(),locate:()=>this.locateCurrentSong(),visualizer:()=>this.toggleVisualizer(),visualizerMode:()=>this.toggleVisualizerMode(),search:()=>document.querySelector('#songSearch')?.focus(),queue:()=>this.playlistOpen?this.playlistOpen=false:this.openPlaylistPanel(),import:()=>{if(this.adminEnabled)this.openImportPanel()},help:()=>this.shortcutOpen=true,escape:()=>{if(this.shortcutOpen)this.shortcutOpen=false;else if(this.playlistOpen)this.playlistOpen=false;else if(this.sidebarOpen)this.sidebarOpen=false;else if(this.importOpen)this.importOpen=false;else this.clearSearch()}})[action]?.()},
    removeFromQueue(index){this.queue.splice(index,1);if(index<=this.queueIndex)this.queueIndex--},
    openPlaylistPanel(){this.playlistOpen=true;void this.loadPlaylists()},
    async loadPlaylists(force=false){
        if(this._playlistsLoaded&&!force)return true;
        if(this._playlistsRequest)return this._playlistsRequest;
        this._playlistsRequest=(async()=>{try{const res=await fetch('/api/playlists');if(!res.ok)throw new Error(`HTTP ${res.status}`);this.playlists=await res.json();this._playlistsLoaded=true;return true}catch(error){this._playlistsLoaded=false;this.notify('播放列表加载失败','error');return false}finally{this._playlistsRequest=null}})();
        return this._playlistsRequest
    },
    async saveAsPlaylist(){const name=this.newPlaylistName.trim();if(!name||!this.queue.length)return;try{const res=await fetch('/api/playlists',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name,description:'',songIds:this.queue.map(song=>song.id)})});if(!res.ok){const payload=await res.json().catch(()=>null);throw new Error(payload?.reason||`HTTP ${res.status}`)}const list=await res.json();this.newPlaylistName='';await this.loadPlaylists(true);this.notify(`播放列表已保存 · ${list.songCount??this.queue.length} 首`)}catch(error){this.notify(`保存播放列表失败：${error?.message||'未知错误'}`,'error')}},
    async loadPlaylist(id){try{const res=await fetch(`/api/playlists/${id}`),list=await res.json();if(list.songs?.length){this.queue=list.songs;this.playSong(list.songs[0]);this.playlistOpen=false}}catch(error){this.notify('播放列表加载失败','error')}},
    async deletePlaylist(id){try{const res=await fetch(`/api/playlists/${id}`,{method:'DELETE'});if(!res.ok)throw new Error(`HTTP ${res.status}`);await this.loadPlaylists(true)}catch(error){this.notify('删除播放列表失败','error')}},
    notify(message,type='success'){const id=++this.toastId;this.toasts.push({id,message,type});setTimeout(()=>this.dismissToast(id),3600)},dismissToast(id){this.toasts=this.toasts.filter(x=>x.id!==id)}
}}

globalThis.OrzUI={clamp,formatDuration,isEditableTarget,releaseShortcutFocus,shortcutAction,formats:ORZ_FORMATS};
globalThis.playerApp=playerApp;
if(typeof module!=='undefined')module.exports=globalThis.OrzUI;
