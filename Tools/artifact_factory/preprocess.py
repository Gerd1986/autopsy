# coding=utf-8
"""Shared Python 3 / Jython 2.7 command and plugin-folder resolver."""
import os
import sys
import shlex
import subprocess


def _pre_windows():
    if os.name == 'nt' or getattr(os, '_name', '') == 'nt' or sys.platform.startswith('win'): return True
    if os.name == 'java':
        from java.lang import System
        return System.getProperty('os.name').lower().startswith('windows')
    return False


def _pre_split(command):
    if not _pre_windows(): return shlex.split(command)
    # Windows CRT quoting: backslashes are special only immediately before quotes.
    args=[];token=[];quoted=False;active=False;i=0
    while i<len(command):
        c=command[i]
        if c in ' \t' and not quoted:
            if active:args.append(''.join(token));token=[];active=False
            i+=1;continue
        if c=='\\':
            n=0
            while i<len(command) and command[i]=='\\':n+=1;i+=1
            if i<len(command) and command[i]=='"':
                token.extend('\\'*(n//2));active=True
                if n%2:token.append('"')
                else:quoted=not quoted
                i+=1
            else:token.extend('\\'*n);active=True
            continue
        if c=='"':quoted=not quoted;active=True;i+=1;continue
        token.append(c);active=True;i+=1
    if quoted:raise ValueError('Unclosed quote in preprocessing command')
    if active:args.append(''.join(token))
    return args


def _pre_quote(args):
    if _pre_windows():return subprocess.list2cmdline(args)
    return ' '.join("'"+value.replace("'", "'\\''")+"'" for value in args)


def _pre_roots(meta):
    roots=[]
    for value in (meta.get('tool_dir'),meta.get('script_dir'),meta.get('resource_dir')):
        if value and os.path.isdir(value):
            value=os.path.abspath(value)
            if value not in roots:roots.append(value)
    return roots


def _pre_python():
    if os.name != 'java':return sys.executable
    try:
        from org.openide.modules import InstalledFileLocator
        runtime=InstalledFileLocator.getDefault().locate('nand/python/python.exe',None,False)
        if runtime is not None:return runtime.getAbsolutePath()
    except Exception:pass
    return 'python'


def resolve_preprocess(command, meta, input_path, output_path, inputs):
    roots=_pre_roots(meta)
    command=command.replace('{inputs}',_pre_quote(inputs))
    for n,path in enumerate(inputs,1):command=command.replace('{input%d}'%n,path)
    command=command.replace('{input}',input_path).replace('{output}',output_path)
    command=command.replace('{script_dir}',meta.get('script_dir') or '').replace('{tool_dir}',meta.get('tool_dir') or meta.get('script_dir') or '')
    command=command.replace('{input_dir}',input_path if os.path.isdir(input_path) else os.path.dirname(input_path))
    shell=bool(meta.get('_step_shell',False))
    if shell:
        # Keep intentional shell syntax intact. Use explicit placeholders/paths.
        return command, (meta.get('tool_dir') or meta.get('script_dir') or None)
    args=_pre_split(command)
    if not args:raise ValueError('Empty preprocessing command')
    for index,value in enumerate(args):
        if index!=0 and not value.lower().endswith(('.py','.pl')):continue
        if not value.lower().endswith(('.py','.pl','.exe')) or os.path.isabs(value):continue
        for root in roots:
            candidate=os.path.normpath(os.path.join(root,value))
            if os.path.isfile(candidate):args[index]=candidate;break
    if args[0].lower().endswith('.py'):args.insert(0,_pre_python())
    elif args[0].lower().endswith('.pl'):args.insert(0,'perl')
    cwd=meta.get('tool_dir') or meta.get('script_dir')
    if cwd and not os.path.isdir(cwd):raise ValueError('Plugin/tool folder does not exist: '+cwd)
    return args,cwd or None


def preprocessing_environment():
    env=dict(os.environ)
    env.setdefault('PYTHONIOENCODING','utf-8')
    return env
