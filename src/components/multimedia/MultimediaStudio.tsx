import React, { useEffect, useMemo, useRef, useState } from "react";

type StudioMode = "editor" | "ai";
type MediaKind = "video" | "image" | "audio";
type Tool = "adjust" | "crop" | "text" | "audio" | "speed";
type Aspect = "9:16" | "16:9" | "1:1" | "free";
type Filter = "none" | "bw" | "sepia" | "vintage" | "cyberpunk";
type AiMode = "image" | "video" | "script" | "tts";

export interface TimelineLayer {
  id: string; name: string; kind: MediaKind | "text"; start: number; duration: number; visible: boolean;
  text?: string; font?: string;
}
export interface MultimediaStudioProps {
  apiBaseUrl?: string;
  onExport?: (state: EditorState) => Promise<void> | void;
}
export interface EditorState {
  brightness: number; contrast: number; saturation: number; filter: Filter; aspect: Aspect;
  speed: number; layers: TimelineLayer[]; currentTime: number;
}

const DARK = "#0F172A";
const uid = () => crypto.randomUUID();

export function useTimeline() {
  const [layers, setLayers] = useState<TimelineLayer[]>([]);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(30);
  const addLayer = (layer: Omit<TimelineLayer, "id">) => setLayers(v => [...v, { ...layer, id: uid() }]);
  const updateLayer = (id: string, patch: Partial<TimelineLayer>) => setLayers(v => v.map(x => x.id === id ? { ...x, ...patch } : x));
  const removeLayer = (id: string) => setLayers(v => v.filter(x => x.id !== id));
  return { layers, setLayers, currentTime, setCurrentTime, duration, setDuration, addLayer, updateLayer, removeLayer };
}

export function useAiStudio(apiBaseUrl = "") {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const cancel = () => { abortRef.current?.abort(); abortRef.current = null; setBusy(false); };

  const generate = async (mode: AiMode, prompt: string, style?: string) => {
    cancel();
    const controller = new AbortController();
    abortRef.current = controller;
    setBusy(true); setError(null);
    try {
      const res = await fetch(`${apiBaseUrl.replace(/\/$/, "")}/v1/ai/generate`, {
        method: "POST", headers: { "content-type": "application/json" },
        signal: controller.signal,
        body: JSON.stringify({
          messages: [{ role: "user", content: `IAC33 MEDIA MODE=${mode}; STYLE=${style || "default"}; REQUEST=${prompt}` }],
          timeoutMs: 12000,
        }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.error || `AI HTTP ${res.status}`);
      return String(body.text || "");
    } finally {
      if (abortRef.current === controller) { abortRef.current = null; setBusy(false); }
    }
  };
  useEffect(() => () => abortRef.current?.abort(), []);
  return { generate, cancel, busy, error };
}

function Preview({ videoRef, state }: { videoRef: React.RefObject<HTMLVideoElement>; state: EditorState }) {
  const aspect = state.aspect === "9:16" ? "9 / 16" : state.aspect === "1:1" ? "1 / 1" : "16 / 9";
  const filter = state.filter === "bw" ? "grayscale(1)" :
    state.filter === "sepia" ? "sepia(1)" :
    state.filter === "vintage" ? "sepia(.35) saturate(.8) contrast(1.05)" :
    state.filter === "cyberpunk" ? "saturate(1.6) contrast(1.2) hue-rotate(12deg)" : "none";
  return <div style={{ width: "100%", aspectRatio: aspect, background: "#020617", borderRadius: 14, overflow: "hidden", display: "grid", placeItems: "center" }}>
    <video ref={videoRef} controls playsInline style={{ width: "100%", height: "100%", objectFit: "contain", filter: `brightness(${state.brightness}%) contrast(${state.contrast}%) saturate(${state.saturation}%) ${filter}` }} />
  </div>;
}

export default function MultimediaStudio({ apiBaseUrl = "", onExport }: MultimediaStudioProps) {
  const [mode, setMode] = useState<StudioMode>("editor");
  const [tool, setTool] = useState<Tool>("adjust");
  const [mediaUrl, setMediaUrl] = useState<string | null>(null);
  const [mediaKind, setMediaKind] = useState<MediaKind | null>(null);
  const [state, setState] = useState<EditorState>({ brightness:100, contrast:100, saturation:100, filter:"none", aspect:"16:9", speed:1, layers:[], currentTime:0 });
  const videoRef = useRef<HTMLVideoElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const timeline = useTimeline();
  const ai = useAiStudio(apiBaseUrl);
  const [aiMode, setAiMode] = useState<AiMode>("script");
  const [prompt, setPrompt] = useState("");
  const [style, setStyle] = useState("cinematic");
  const [aiOutput, setAiOutput] = useState("");

  const openMedia = (file: File) => {
    if (!/^((video|image|audio)\/)/.test(file.type)) return;
    const url = URL.createObjectURL(file);
    setMediaUrl(old => { if (old) URL.revokeObjectURL(old); return url; });
    setMediaKind(file.type.startsWith("video/") ? "video" : file.type.startsWith("image/") ? "image" : "audio");
    timeline.addLayer({ name:file.name, kind:file.type.startsWith("video/")?"video":file.type.startsWith("image/")?"image":"audio", start:0, duration:30, visible:true });
  };

  useEffect(() => () => { if (mediaUrl) URL.revokeObjectURL(mediaUrl); }, [mediaUrl]);

  const update = (patch: Partial<EditorState>) => setState(v => ({ ...v, ...patch }));
  const runAi = async () => {
    if (!prompt.trim()) return;
    try { setAiOutput(await ai.generate(aiMode, prompt, style)); }
    catch (e) { if ((e as DOMException).name !== "AbortError") setAiOutput(`Error: ${e instanceof Error ? e.message : "IA no disponible"}`); }
  };
  const addText = () => timeline.addLayer({ name:"Texto", kind:"text", start:state.currentTime, duration:5, visible:true, text:"Nuevo texto", font:"Inter" });

  const css = `
    .iac33-ms{min-height:100%;background:${DARK};color:#e2e8f0;font-family:Inter,system-ui,sans-serif;display:flex;flex-direction:column}
    .iac33-ms button{color:#e2e8f0;background:#1e293b;border:1px solid #334155;border-radius:10px;padding:10px 12px;font-weight:600}
    .iac33-ms button.active{background:#1d4ed8;border-color:#2563eb}.iac33-ms input,.iac33-ms select,.iac33-ms textarea{background:#0b1220;color:#e2e8f0;border:1px solid #334155;border-radius:9px;padding:9px}
    .ms-top{display:flex;gap:8px;padding:12px;position:sticky;top:0;z-index:4;background:#0f172aee;backdrop-filter:blur(12px)}
    .ms-main{padding:12px;display:grid;gap:12px}.ms-panel{background:#111827;border:1px solid #243244;border-radius:14px;padding:12px}.ms-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.ms-scroll{display:flex;gap:8px;overflow-x:auto;padding:8px 0;scrollbar-width:none}.ms-scroll button{white-space:nowrap}.ms-range{width:100%}.ms-track{display:flex;gap:6px;overflow-x:auto;padding:8px 0}.ms-layer{min-width:130px;background:#172033;border:1px solid #334155;border-radius:8px;padding:8px}.ms-bottom{position:sticky;bottom:0;background:#0f172af2;border-top:1px solid #243244;padding:8px;display:flex;gap:8px;overflow-x:auto}.ms-bottom button{white-space:nowrap}
  `;

  return <div className="iac33-ms">
    <style>{css}</style>
    <div className="ms-top">
      <button className={mode==="editor"?"active":""} onClick={()=>setMode("editor")}>Editor Profesional</button>
      <button className={mode==="ai"?"active":""} onClick={()=>setMode("ai")}>Estudio IA</button>
    </div>

    <main className="ms-main">
      {mode==="editor" ? <>
        <section className="ms-panel">
          <div className="ms-row"><strong>Vista previa</strong><span style={{opacity:.7}}>{mediaKind || "sin medio"}</span><button onClick={()=>fileRef.current?.click()}>Abrir</button><input ref={fileRef} hidden type="file" accept="video/*,image/*,audio/*" onChange={e=>e.target.files?.[0]&&openMedia(e.target.files[0])}/></div>
          {mediaUrl && mediaKind==="video" ? <Preview videoRef={videoRef} state={state}/> :
            mediaUrl && mediaKind==="image" ? <img src={mediaUrl} alt="Vista previa" style={{width:"100%",maxHeight:420,objectFit:"contain",aspectRatio:state.aspect,filter:`brightness(${state.brightness}%) contrast(${state.contrast}%) saturate(${state.saturation}%)`}}/> :
            <div style={{padding:50,textAlign:"center",opacity:.65}}>Carga un vídeo, imagen o audio</div>}
        </section>

        <section className="ms-panel">
          <div className="ms-row"><strong>Timeline / capas</strong><span>{timeline.layers.length} capas</span></div>
          <input className="ms-range" type="range" min="0" max={timeline.duration} step=".01" value={state.currentTime} onChange={e=>{const t=+e.target.value;update({currentTime:t});timeline.setCurrentTime(t);if(videoRef.current)videoRef.current.currentTime=t}}/>
          <div className="ms-track">{timeline.layers.map(l=><div className="ms-layer" key={l.id}><b>{l.name}</b><small style={{display:"block",opacity:.7}}>{l.kind} · {l.start.toFixed(1)}s → {(l.start+l.duration).toFixed(1)}s</small></div>)}</div>
        </section>

        <section className="ms-panel">
          <div className="ms-scroll">{(["adjust","crop","text","audio","speed"] as Tool[]).map(x=><button className={tool===x?"active":""} onClick={()=>setTool(x)} key={x}>{x==="adjust"?"Ajustes":x==="crop"?"Recorte":x==="text"?"Texto":x==="audio"?"Audio":"Velocidad"}</button>)}</div>
          {tool==="adjust" && <div style={{display:"grid",gap:8}}>
            {([["Brillo","brightness",100],["Contraste","contrast",100],["Saturación","saturation",100]] as const).map(([label,key,base])=><label key={key}>{label} {state[key]}%<input className="ms-range" type="range" min="0" max="200" value={state[key]} onChange={e=>update({[key]:+e.target.value} as Partial<EditorState>)}/></label>)}
            <select value={state.filter} onChange={e=>update({filter:e.target.value as Filter})}><option value="none">Sin filtro</option><option value="bw">B/N</option><option value="sepia">Sepia</option><option value="vintage">Vintage</option><option value="cyberpunk">Cyberpunk</option></select>
          </div>}
          {tool==="crop" && <div className="ms-scroll">{(["9:16","16:9","1:1","free"] as Aspect[]).map(x=><button className={state.aspect===x?"active":""} key={x} onClick={()=>update({aspect:x})}>{x}</button>)}</div>}
          {tool==="text" && <div className="ms-row"><input placeholder="Texto de capa" onChange={e=>{}}/><select><option>Inter</option><option>Roboto</option><option>Montserrat</option><option>Playfair Display</option></select><button onClick={addText}>Añadir capa</button></div>}
          {tool==="audio" && <div className="ms-row"><button onClick={()=>fileRef.current?.click()}>Añadir pista de audio</button><span style={{opacity:.7}}>La pista queda como capa secundaria.</span></div>}
          {tool==="speed" && <div className="ms-row">{[.25,.5,1,1.5,2,4].map(s=><button className={state.speed===s?"active":""} key={s} onClick={()=>{update({speed:s});if(videoRef.current)videoRef.current.playbackRate=s}}>{s}x</button>)}</div>}
        </section>

        <section className="ms-panel">
          <div className="ms-row"><button onClick={()=>onExport?.(state)}>Exportación limpia</button><span style={{opacity:.65}}>Sin overlays de diagnóstico.</span></div>
        </section>
      </> : <>
        <section className="ms-panel">
          <div className="ms-scroll">{(["image","video","script","tts"] as AiMode[]).map(x=><button className={aiMode===x?"active":""} key={x} onClick={()=>setAiMode(x)}>{x==="image"?"Imagen IA":x==="video"?"Vídeo IA":x==="script"?"Guiones y Copy":"Voz IA"}</button>)}</div>
          <textarea rows={5} style={{width:"100%",boxSizing:"border-box"}} placeholder={aiMode==="tts"?"Texto para locución…":"Describe lo que quieres crear…"} value={prompt} onChange={e=>setPrompt(e.target.value)}/>
          <div className="ms-row" style={{marginTop:8}}><select value={style} onChange={e=>setStyle(e.target.value)}><option>cinematic</option><option>photorealistic</option><option>anime</option><option>documentary</option><option>cyberpunk</option><option>minimal</option></select><button onClick={runAi} disabled={ai.busy}>{ai.busy?"Generando…":"Generar"}</button><button onClick={ai.cancel} disabled={!ai.busy}>Cancelar</button></div>
          {aiOutput && <pre style={{whiteSpace:"pre-wrap",marginTop:10}}>{aiOutput}</pre>}
          <p style={{opacity:.6,fontSize:12}}>El backend actual de IAC33 expone /v1/ai/generate para texto. Imagen, vídeo y TTS requieren endpoints/proveedores de medios dedicados antes de poder producir archivos binarios reales.</p>
        </section>
      </>}

      <section className="ms-panel">
        <strong>Capas</strong>
        {timeline.layers.map(l=><div className="ms-row" key={l.id} style={{justifyContent:"space-between",padding:"6px 0"}}><span>{l.name}</span><button onClick={()=>timeline.removeLayer(l.id)}>Eliminar</button></div>)}
      </section>
    </main>

    <nav className="ms-bottom">
      {mode==="editor" ? ["Ajustes","Recorte","Texto","Audio","Velocidad"].map((x,i)=><button key={x} onClick={()=>setTool((["adjust","crop","text","audio","speed"] as Tool[])[i])}>{x}</button>) :
        ["Imagen IA","Vídeo IA","Guiones","Voz IA"].map((x,i)=><button key={x} onClick={()=>setAiMode((["image","video","script","tts"] as AiMode[])[i])}>{x}</button>)}
    </nav>
  </div>;
}
