// Public zero-entropy vectors only. Independent Ethers v6 EIP-712 validation.
// Build core/cmd/cli to artifacts/spot-native-cli first. Reuses pinned Ethers
// from the DeFi test setup documented in scripts/test_defi_evm.cjs.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawnSync}=require('node:child_process');const root=path.resolve(__dirname,'..');
const ethers=require(path.join(root,'artifacts/defi-evm/node_modules/ethers'));
function native(r){const p=spawnSync(path.join(root,'artifacts/spot-native-cli'),[],{input:JSON.stringify(r)+'\n',encoding:'utf8'});assert.equal(p.status,0,p.stderr);const v=JSON.parse(p.stdout);assert.equal(v.error,undefined,JSON.stringify(v));return v.result;}
const domain={name:'Pearl OTC',version:'1'},types={Request:[{name:'action',type:'string'},{name:'payloadHash',type:'bytes32'},{name:'nonce',type:'uint256'},{name:'issuedAt',type:'uint256'}]};
(async()=>{const results=[];
 for(const n of [16,20,24,28,32]){const entropy=Buffer.alloc(n).toString('base64'),words=ethers.Mnemonic.entropyToPhrase(Buffer.alloc(n)),wallet=ethers.HDNodeWallet.fromPhrase(words),pearl=native({action:'address',entropy}).address;
  for(const action of ['place_order','cancel_order','withdraw_usdc','withdraw_prl']){const now=Math.floor(Date.now()/1000),plan={from:wallet.address,action:action.startsWith('withdraw')?'withdraw':action,nonce:Date.now()*1024+n,issuedAt:now,expires:now+240};
   if(action==='place_order')Object.assign(plan,{side:'sell',price:'2160000',quantity:'560000000'});
   if(action==='cancel_order')Object.assign(plan,{orderId:'3038666'});
   if(action==='withdraw_usdc')Object.assign(plan,{asset:'USDC-ARB',destination:wallet.address,quantity:'14256000'});
   if(action==='withdraw_prl')Object.assign(plan,{asset:'PRL',destination:pearl,quantity:'200000000'});
   const a=native({action:'tradesign',entropy,trade:plan}),value={action:plan.action,payloadHash:ethers.keccak256(ethers.toUtf8Bytes(a.payload)),nonce:a.nonce,issuedAt:a.issued_at};
   const digest=ethers.TypedDataEncoder.hash(domain,types,value);assert.equal(native({action:'tradeintent',trade:plan}).digest,digest);assert.equal(ethers.verifyTypedData(domain,types,value,a.signature),wallet.address);assert.equal(a.signature,await wallet.signTypedData(domain,types,value));
   assert.equal(native({action:'tradeverify',trade:plan,tradeAuth:a}).from,wallet.address);assert.deepEqual(Object.keys(a).sort(),['eth_address','issued_at','nonce','payload','signature']);
   results.push({words:words.split(' ').length,operation:action,address:wallet.address,digest,signature_verified:true});
  }
 }
 const report={result:'PASS',scope:'Independent Ethers v6 typed-data hashes, deterministic signatures and recovery for all five mnemonic lengths; no network writes',vectors:results,mainnet_orders:0,mainnet_withdrawals:0};fs.writeFileSync(path.join(root,'artifacts/spot-signatures.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({result:report.result,vectors:results.length,network_writes:0}));
})().catch(e=>{console.error(e);process.exit(1);});
