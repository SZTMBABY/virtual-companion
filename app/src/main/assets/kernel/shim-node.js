// Browser-safe shim for the only four Node builtins the kernel touches.
// createHash is only used by CCR content-addressing (disabled here);
// fs/path are only used by prompt "packs" (disabled here);
// createRequire is only used to optionally load @anthropic-ai/tokenizer.
export function createHash(){var s='';return{update:function(t){s+=String(t);return this;},digest:function(){var h=2166136261>>>0;for(var i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619)>>>0;}return ('00000000'+h.toString(16)).slice(-8);}};}
export function readFileSync(){throw new Error('fs unavailable');}
export function readdirSync(){return [];}
export function join(){var a=[];for(var i=0;i<arguments.length;i++)if(arguments[i])a.push(arguments[i]);return a.join('/');}
export function resolve(){return join.apply(null,arguments);}
export function dirname(p){return String(p).replace(/\/[^/]*$/,'');}
export function basename(p){return String(p).split('/').pop();}
export function isAbsolute(p){return String(p).charAt(0)==='/';}
export function createRequire(){return function(){throw new Error('no require in browser');};}
export var sep='/';
export default {join:join,resolve:resolve,dirname:dirname,basename:basename,isAbsolute:isAbsolute,sep:sep};
