#!/usr/bin/env python3
"""Plugin-folder resolution, directory input and standalone Jython pipeline."""
import argparse,json,os,subprocess,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'Tools/artifact_factory'))
import factory,preprocess
from template_core import EMBEDDED_PARSER_BLOCK

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--jython',type=Path);args=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix='plugin folder ') as td:
        root=Path(td);tools=root/'tools folder';tools.mkdir();source=root/'source folder';source.mkdir()
        (source/'a.txt').write_text('a');(source/'b.txt').write_text('b')
        script=tools/'decoder.py'
        script.write_text("import sys,os\nprint('name,phone')\nprint(open('config.txt').read().strip()+','+str(len(os.listdir(sys.argv[1]))))\n")
        (tools/'config.txt').write_text('TwoFiles')
        for name in ('decoder.pl','decoder.exe'):(tools/name).write_text('')
        meta={'path':str(source),'source_type':'csv','script_dir':str(root),'tool_dir':str(tools),'preprocess_steps':[{'command':'decoder.py "{input}"','shell':False,'output_suffix':'.csv'}]}
        result=factory.dispatch({'action':'preview','meta':meta});assert result['rows']==[{'name':'TwoFiles','phone':'2'}]
        command,cwd=preprocess.resolve_preprocess('perl decoder.pl "{input}"',meta,str(source),'unused',[])
        assert command[1]==str(tools/'decoder.pl') and cwd==str(tools)
        command,cwd=preprocess.resolve_preprocess('decoder.exe "{input}"',meta,str(source),'unused',[])
        assert command[0]==str(tools/'decoder.exe')
        # Default lookup in the plugin folder without a selected tool override.
        command,cwd=preprocess.resolve_preprocess('python decoder.py "{input}"',{'script_dir':str(tools)},str(source),'unused',[])
        assert command[1]==str(script)
        # Windows quoting is tested even on POSIX: exact argv -> string -> argv.
        previous=preprocess._pre_windows;preprocess._pre_windows=lambda:True
        vectors=[['python',r'C:\folder with spaces\decoder.py','C:\\input\\'],['x.exe','','a"b','space and slash\\'],['perl','script.pl','x\ty']]
        for values in vectors:assert preprocess._pre_split(subprocess.list2cmdline(values))==values
        preprocess._pre_windows=previous
        code=factory.dispatch({'action':'template','meta':meta,'plugin_type':'Mobile','mapping':{'Vorname':'name'},'filename':'source folder','module_name':'Folder','tool_directory':'module_inputs/tools'})['code']
        assert str(tools) not in code and 'module_inputs/tools' in code
        if args.jython:
            # Use an external CPython command in Jython, since this test runs outside Autopsy's locator.
            embedded=meta.copy();embedded['preprocess_steps']=[{'command':'"'+sys.executable+'" decoder.py "{input}"','output_suffix':'.csv','shell':False}]
            runner=root/'run_jython.py'
            runner.write_text('# coding=utf-8\n'+EMBEDDED_PARSER_BLOCK+'\nimport json\nmeta=json.loads('+repr(json.dumps(embedded))+')\nrows=parse_rows(meta,{"Vorname":"name"},"Mobile")\nassert rows[0]["firstname"]=="TwoFiles"\n',encoding='utf8')
            subprocess.run(['java','-cp',str(args.jython),'org.python.util.jython',str(runner)],check=True)
    print('PASS: bare Python, Perl and EXE plugin/tool lookup; relative config working directory; folder input; Windows quoted arguments; no local tool path embedded'+('; real Jython preprocessing' if args.jython else ''))
if __name__=='__main__':main()
