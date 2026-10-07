// Isolated local EVM test, using PUBLIC zero-entropy test vectors only.
// npm install --prefix artifacts/defi-evm ethers@6.17.0 ganache@7.9.2 solc@0.8.30
// Build core/cmd/cli to artifacts/defi-native-cli, then node scripts/test_defi_evm.cjs.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const ethers = require(path.join(root, 'artifacts/defi-evm/node_modules/ethers'));
const ganache = require(path.join(root, 'artifacts/defi-evm/node_modules/ganache'));
const solc = require(path.join(root, 'artifacts/defi-evm/node_modules/solc'));
const USDC='0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48', POOL='0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2', ATOKEN='0x98c23e9d8f34fefb1b7bd6a91b7ff122f4e16f5c';
const mnemonic='abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
function native(request) {
  const r=spawnSync(path.join(root,'artifacts/defi-native-cli'),[],{input:JSON.stringify(request)+'\n',encoding:'utf8'});
  assert.equal(r.status,0,r.stderr);const v=JSON.parse(r.stdout);assert.equal(v.error,undefined,JSON.stringify(v));return v.result;
}
const source=`pragma solidity ^0.8.30;
contract Token {
 mapping(address=>uint) public balanceOf;
 mapping(address=>mapping(address=>uint)) public allowance;
 uint public totalSupply;
 function decimals() external pure returns(uint8){return 6;}
 function mint(address a,uint n) external {balanceOf[a]+=n;totalSupply+=n;}
 function approve(address s,uint n) external returns(bool){allowance[msg.sender][s]=n;return true;}
 function transfer(address to,uint n) external returns(bool){require(balanceOf[msg.sender]>=n,"balance");balanceOf[msg.sender]-=n;balanceOf[to]+=n;return true;}
 function transferFrom(address f,address t,uint n) external returns(bool){require(allowance[f][msg.sender]>=n,"allowance");require(balanceOf[f]>=n,"balance");allowance[f][msg.sender]-=n;balanceOf[f]-=n;balanceOf[t]+=n;return true;}
 function release(address f,address to,uint n) external {require(msg.sender==address(0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2));require(balanceOf[f]>=n,"deposit");balanceOf[f]-=n;totalSupply-=n;Token(address(0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48)).transfer(to,n);}
}
contract Pool {
 function supply(address asset,uint amount,address onBehalfOf,uint16 referral) external {require(asset==address(0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48)&&onBehalfOf==msg.sender&&referral==0);Token(asset).transferFrom(msg.sender,address(0x98C23E9d8f34FEFb1B7BD6a91B7FF122F4e16F5c),amount);Token(address(0x98C23E9d8f34FEFb1B7BD6a91B7FF122F4e16F5c)).mint(onBehalfOf,amount);}
 function withdraw(address asset,uint amount,address to) external returns(uint){require(asset==address(0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48)&&to==msg.sender);Token(address(0x98C23E9d8f34FEFb1B7BD6a91B7FF122F4e16F5c)).release(msg.sender,to,amount);return amount;}
}`;
async function main() {
  const reference=ethers.HDNodeWallet.fromPhrase(mnemonic);
  assert.equal(native({action:'ethidentity',entropy:Buffer.alloc(16).toString('base64')}).address,reference.address);
  const lengths=[];
  for(const n of [16,20,24,28,32]){
    const words=ethers.Mnemonic.entropyToPhrase(Buffer.alloc(n));const expected=ethers.HDNodeWallet.fromPhrase(words);
    const result=native({action:'ethidentity',entropy:Buffer.alloc(n).toString('base64')});assert.equal(result.address,expected.address);lengths.push({words:words.split(' ').length,address:result.address});
  }
  const compiled=JSON.parse(solc.compile(JSON.stringify({language:'Solidity',sources:{'Mock.sol':{content:source}},settings:{evmVersion:'shanghai',outputSelection:{'*':{'*':['abi','evm.deployedBytecode.object']}}}})));
  assert(!compiled.errors?.some(e=>e.severity==='error'),JSON.stringify(compiled.errors));
  const local=ganache.provider({chain:{chainId:1,hardfork:'shanghai'},wallet:{mnemonic,defaultBalance:100},logging:{quiet:true}});
  const rpc=async(method,params=[])=>local.request({method,params});
  const provider=new ethers.BrowserProvider(local);const admin=await provider.getSigner();
  const contracts=compiled.contracts['Mock.sol'];
  await rpc('evm_setAccountCode',[USDC,'0x'+contracts.Token.evm.deployedBytecode.object]);
  await rpc('evm_setAccountCode',[ATOKEN,'0x'+contracts.Token.evm.deployedBytecode.object]);
  await rpc('evm_setAccountCode',[POOL,'0x'+contracts.Pool.evm.deployedBytecode.object]);
  const token=new ethers.Contract(USDC,contracts.Token.abi,admin),atoken=new ethers.Contract(ATOKEN,contracts.Token.abi,admin);
  await (await token.mint(reference.address,100_000_000n)).wait();
  const results=[];
  for(const [operation,amount] of [['approve','12500000'],['supply','12500000'],['withdraw','5000000']]) {
    const plan={operation,amount,chainId:1,from:reference.address,nonce:BigInt(await rpc('eth_getTransactionCount',[reference.address,'pending'])).toString(),gas:'300000',maxFeePerGas:'30000000000',maxPriorityFeePerGas:'1000000000',expires:Math.floor(Date.now()/1000)+240};
    const signed=native({action:'ethsign',entropy:Buffer.alloc(16).toString('base64'),ethereum:plan});
    const tx=ethers.Transaction.from(signed.raw);assert.equal(tx.from,reference.address);assert.equal(tx.hash,signed.hash);assert.equal(tx.chainId,1n);assert.equal(tx.value,0n);assert.equal(tx.type,2);
    const expectedData=new ethers.Interface(operation==='approve'?contracts.Token.abi:contracts.Pool.abi).encodeFunctionData(operation,operation==='approve'?[POOL,BigInt(amount)]:operation==='supply'?[USDC,BigInt(amount),reference.address,0]:[USDC,BigInt(amount),reference.address]);
    assert.equal(tx.data,expectedData);assert.equal(tx.to.toLowerCase(),operation==='approve'?USDC:POOL);
    const actualHash=await rpc('eth_sendRawTransaction',[signed.raw]);assert.equal(actualHash,signed.hash);
    const receipt=await rpc('eth_getTransactionReceipt',[actualHash]);assert.equal(receipt.status,'0x1');
    results.push({operation,hash:actualHash,status:receipt.status,nonce:tx.nonce,gasUsed:receipt.gasUsed});
    if(operation==='approve'){assert.equal(await token.allowance(reference.address,POOL),12_500_000n);assert.equal(await atoken.balanceOf(reference.address),0n);}
    if(operation==='supply'){assert.equal(await token.allowance(reference.address,POOL),0n);assert.equal(await atoken.balanceOf(reference.address),12_500_000n);assert.equal(await token.balanceOf(reference.address),87_500_000n);}
  }
  assert.equal(await atoken.balanceOf(reference.address),7_500_000n);assert.equal(await token.balanceOf(reference.address),92_500_000n);
  // A supported but unauthorised supply must revert in the VM; no duplicate action is inferred.
  const failedPlan={operation:'supply',amount:'1',chainId:1,from:reference.address,nonce:BigInt(await rpc('eth_getTransactionCount',[reference.address,'pending'])).toString(),gas:'300000',maxFeePerGas:'30000000000',maxPriorityFeePerGas:'1000000000',expires:Math.floor(Date.now()/1000)+240};
  const failed=native({action:'ethsign',entropy:Buffer.alloc(16).toString('base64'),ethereum:failedPlan});await rpc('eth_sendRawTransaction',[failed.raw]);assert.equal((await rpc('eth_getTransactionReceipt',[failed.hash])).status,'0x0');
  const report={status:'PASS',scope:'isolated local mock contracts; not Aave production contract or a mainnet fork',ethers:ethers.version,solc:solc.version(),bip39_vectors:lengths,transactions:results,reverted_supply:failed.hash,final_usdc:'92.5',final_aUSDC:'7.5',no_mainnet_broadcast:true};
  fs.writeFileSync(path.join(root,'artifacts/defi-local-evm.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));await local.disconnect();
}
main().catch(e=>{console.error(e);process.exitCode=1});
