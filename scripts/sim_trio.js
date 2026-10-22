// How accurate can a 3-phone Bluetooth RSSI mesh be? Monte Carlo with the same maths as the app.
// Noise model (all in dB, typical published ranges, NOT measured on our phones):
//   slow shadowing per link  : same in both directions (radio paths are reciprocal)
//   per-phone offset         : transmit power + receive sensitivity differ between phone models
//   fast fading per sample   : averaged down by the median over the 5 s window (about 30 samples)
function rng(seed){let a=seed>>>0;const f=()=>{a|=0;a=a+0x6D2B79F5|0;let t=Math.imul(a^a>>>15,1|a);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296};f.g=()=>{let u=0;while(!u)u=f();return Math.sqrt(-2*Math.log(u))*Math.cos(2*Math.PI*f())};return f}
const med=a=>{const s=[...a].sort((x,y)=>x-y);return s.length%2?s[s.length>>1]:(s[s.length/2-1]+s[s.length/2])/2};
const q=(a,f)=>{const s=[...a].sort((x,y)=>x-y);return s[Math.min(s.length-1,Math.floor(f*s.length))]};
function run(truth,{slow,dev,fast,nTrue,nAssumed,known},trials,seed){
  const r=rng(seed),R1=-59,out={ab:[],ac:[],bc:[],shape:[]};
  const P=(d,n)=>R1-10*n*Math.log10(d), D=(rs,r1,n)=>Math.pow(10,(r1-rs)/(10*n));
  for(let t=0;t<trials;t++){
    const S=[r.g()*slow,r.g()*slow,r.g()*slow], tx=[0,1,2].map(()=>r.g()*dev), rx=[0,1,2].map(()=>r.g()*dev);
    const pairs=[[0,1],[0,2],[1,2]], est=[];
    pairs.forEach(([i,j],p)=>{
      const dir=(a,b)=>{const xs=[];for(let k=0;k<30;k++)xs.push(P(truth[p],nTrue)+S[p]+tx[b]+rx[a]+r.g()*fast);return med(xs)};
      est.push((dir(i,j)+dir(j,i))/2);
    });
    // calibrate with the real A-B distance (what the app's Calibrate button does), then use the assumed exponent
    const r1=est[0]+10*nAssumed*Math.log10(known);
    const d=est.map(e=>D(e,r1,nAssumed));
    out.ab.push(Math.abs(d[0]-truth[0]));out.ac.push(Math.abs(d[1]-truth[1]));out.bc.push(Math.abs(d[2]-truth[2]));
    out.shape.push((Math.abs(d[1]-truth[1])+Math.abs(d[2]-truth[2]))/2);
  }
  return out;
}
const base={slow:3,dev:1.5,fast:3,nTrue:2.2,nAssumed:2.2};
const cases=[
  ['Equilateral 3 m, calibrated, exponent right',[3,3,3],{...base,known:3}],
  ['3-4-5 m, calibrated, exponent right',[3,4,5],{...base,known:3}],
  ['3-4-5 m, exponent wrong (true 2.8, assumed 2.2)',[3,4,5],{...base,nTrue:2.8,known:3}],
  ['3-4-5 m, quiet room (shadowing 1.5 dB)',[3,4,5],{...base,slow:1.5,known:3}],
];
for(const [name,truth,cfg] of cases){
  const o=run(truth,cfg,4000,7);
  console.log(name);
  console.log('   side error, median / 90th percentile (m):  A-C',med(o.ac).toFixed(2),'/',q(o.ac,.9).toFixed(2),'   B-C',med(o.bc).toFixed(2),'/',q(o.bc,.9).toFixed(2));
}
