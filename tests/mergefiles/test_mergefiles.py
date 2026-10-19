#!/usr/bin/env python3
"""Compare native production merger and previews with uploaded Python reference."""
import ast,base64,hashlib,os,random,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def main():
    reference=ROOT/'Tools/mergefiles/merge_files.py'
    tree=ast.parse(reference.read_text(encoding='utf8'))
    # Execute only stdlib imports and merger functions, never Tk/GUI code.
    allowed=[]
    for node in tree.body:
        if isinstance(node,ast.Import):
            if all(not a.name.startswith('tkinter') for a in node.names):allowed.append(node)
        elif isinstance(node,ast.ImportFrom):
            if node.module not in ('tkinter','__future__'):allowed.append(node)
        elif isinstance(node,ast.ClassDef) and node.name=='Cancelled':allowed.append(node)
        elif isinstance(node,ast.FunctionDef) and node.name in ('parse_order','merge_files'):allowed.append(node)
    ns={};exec(compile(ast.Module(body=allowed,type_ignores=[]),str(reference),'exec'),ns)
    with tempfile.TemporaryDirectory(prefix='mergeFiles parity ') as td:
        work=Path(td);classes=work/'classes';classes.mkdir()
        compiler=['javac'] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
        subprocess.run(compiler+['-encoding','UTF-8','-d',str(classes),str(ROOT/'Core/src/org/sleuthkit/autopsy/casemodule/MergeFiles.java'),str(ROOT/'tests/mergefiles/MergeFilesHarness.java')],check=True)
        def run(dest,block,order,reverse,paths,overwrite=False,mode='normal',good=True):
            args=['java','-cp',str(classes),'org.sleuthkit.autopsy.casemodule.MergeFilesHarness','merge',str(dest),str(block),order,str(reverse).lower(),str(overwrite).lower(),mode,*map(str,paths)]
            result=subprocess.run(args,capture_output=True,text=True,timeout=45)
            assert (result.returncode==0)==good,result.stderr
            return result
        rng=random.Random(335);inputs=[]
        for i,n in enumerate((1073,1027,1013)):
            p=work/('chip %d.bin'%i);p.write_bytes(rng.randbytes(n));inputs.append(p)
        before=[hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs]
        count=0
        for b in (1,2,3,16,128,1024,4096):
            for order in ('','2,1','3,3,1,2,2','1'):
                for reverse in (False,True):
                    expected=work/'reference.bin';ns['merge_files'](inputs,expected,b,order,reverse)
                    output=work/'merged.img';r=run(output,b,order,reverse,inputs,overwrite=output.exists())
                    assert output.read_bytes()==expected.read_bytes() and int(r.stdout)==len(expected.read_bytes())
                    count+=1
        # A tiny, omitted input still limits output; duplicate source selections work.
        tiny=work/'omitted.bin';tiny.write_bytes(b'X')
        output=work/'omitted.img';run(output,2,'1',False,[inputs[0],tiny]);assert output.read_bytes()==b''
        output=work/'duplicate.img';run(output,1,'',False,[inputs[0],inputs[0]]);assert output.read_bytes()==b''.join(bytes([b,b]) for b in inputs[0].read_bytes())
        # Stream blocks larger than memory buffer, including reversal.
        large=[]
        for i in range(2):
            p=work/('large%d.bin'%i);p.write_bytes(rng.randbytes(4*1024*1024+13));large.append(p)
        for reverse in (False,True):
            expected=work/'large reference.bin';ns['merge_files'](large,expected,2*1024*1024+3,'2,1',reverse)
            output=work/('large-'+str(reverse)+'.img');run(output,2*1024*1024+3,'2,1',reverse,large);assert output.read_bytes()==expected.read_bytes()
        # Preview is synchronized by raw offset and maps individual swapped bytes.
        for b in (1,3,16,4096):
            raw=inputs[0].read_bytes();full=len(raw)//b*b
            swapped=b''.join(raw[i:i+b][::-1] for i in range(0,full,b))
            for offset in (0,1,17,1000,10000):
                for after,reverse in ((False,False),(True,False),(True,True)):
                    result=subprocess.check_output(['java','-cp',str(classes),'org.sleuthkit.autopsy.casemodule.MergeFilesHarness','preview',str(inputs[0]),str(offset),str(b),str(after).lower(),str(reverse).lower()],timeout=20)
                    want=(swapped if after and reverse else raw[:full] if after else raw)[offset:offset+256]
                    assert base64.b64decode(result)==want
        output=work/'keep.img';output.write_bytes(b'keep existing target')
        for mode in ('cancel','progress-cancel'):
            run(output,1,'',False,large,overwrite=True,mode=mode,good=False);assert output.read_bytes()==b'keep existing target'
            assert not list(work.glob('.merge-*.partial'))
        run(output,1,'',False,inputs,good=False);assert output.read_bytes()==b'keep existing target'
        run(inputs[0],1,'',False,inputs,overwrite=True,good=False)
        for order in ('0','4','1,','x'):run(work/'bad.img',1,order,False,inputs,good=False)
        run(work/'bad.img',0,'',False,inputs,good=False)
        try:
            alias=work/'hardlink.img';os.link(inputs[0],alias);run(alias,1,'',False,inputs,overwrite=True,good=False)
        except OSError:pass
        assert [hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs]==before
        assert not list(work.glob('.merge-*.partial'))
    print('PASS: %d reference-equivalent merges, repeated/omitted inputs, partial tails, large reversed blocks, synchronized preview offsets/swap, cancellation, aliases and unchanged source bytes'%count)

if __name__=='__main__':main()
