/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.casemodule;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Streaming port of the user-supplied merge_files.py (mergeFiles 0.3.3 semantics). */
final class MergeFiles {
    private static final int BUFFER=1024*1024;
    interface Progress { void update(long written,long total); }
    private MergeFiles(){}

    static int[] order(String text,int count) {
        if(count<1)throw new IllegalArgumentException("Mindestens eine Eingabedatei auswählen.");
        if(text.trim().isEmpty()){int[] result=new int[count];for(int i=0;i<count;i++)result[i]=i;return result;}
        String[] fields=text.split(",",-1);int[] result=new int[fields.length];
        for(int i=0;i<fields.length;i++){
            try{result[i]=Integer.parseInt(fields[i].trim())-1;}catch(NumberFormatException ex){throw new IllegalArgumentException("Reihenfolge als Zahlenliste eingeben, z. B. 3,3,1,2,2.");}
            if(result[i]<0||result[i]>=count)throw new IllegalArgumentException("Dateinummern müssen zwischen 1 und "+count+" liegen.");
        }
        return result;
    }
    static long estimate(List<Path> inputs,long block,String text)throws IOException {
        if(block<1)throw new IllegalArgumentException("Blockgröße muss mindestens 1 Byte sein.");
        int[] indices=order(text,inputs.size());long shortest=Long.MAX_VALUE;
        for(Path input:inputs){if(!Files.isRegularFile(input))throw new IOException("Keine reguläre Datei: "+input);shortest=Math.min(shortest,Files.size(input));}
        try{return Math.multiplyExact(Math.multiplyExact(shortest/block,block),indices.length);}catch(ArithmeticException ex){throw new IOException("Die berechnete Ausgabegröße ist zu groß.",ex);}
    }
    static long merge(List<Path> inputs,Path destination,long block,String text,boolean reverse,
            boolean overwrite,Progress progress,BooleanSupplier cancel)throws IOException,InterruptedException {
        List<Path> paths=new ArrayList<>();for(Path p:inputs)paths.add(p.toRealPath());
        long total=estimate(paths,block,text);int[] indices=order(text,paths.size());
        Path selected=destination.toAbsolutePath().normalize();
        Path target=Files.exists(selected)?selected.toRealPath():selected.getParent().toRealPath().resolve(selected.getFileName());
        rejectAliases(paths,target);
        if(Files.exists(target)&&!overwrite)throw new FileAlreadyExistsException(target.toString());
        if(!Files.isDirectory(target.getParent()))throw new IOException("Ausgabeordner existiert nicht.");
        List<FileChannel> readers=new ArrayList<>();List<BasicFileAttributes> before=new ArrayList<>();Path temporary=null;
        try {
            long rounds=Long.MAX_VALUE;
            for(Path p:paths){FileChannel channel=FileChannel.open(p,StandardOpenOption.READ);readers.add(channel);before.add(Files.readAttributes(p,BasicFileAttributes.class));rounds=Math.min(rounds,channel.size()/block);}
            temporary=Files.createTempFile(target.getParent(),".merge-",".partial");
            long written=0;progress.update(0,total);check(cancel);
            try(OutputStream out=new BufferedOutputStream(Files.newOutputStream(temporary),BUFFER)){
                if(block<=65536){
                    int b=(int)block;
                    int batch=(int)Math.max(1,Math.min(65536,4L*BUFFER/(block*((long)paths.size()+indices.length))));
                    for(long round=0;round<rounds;){
                        check(cancel);int n=(int)Math.min(batch,rounds-round),size=n*b;
                        byte[][] chunks=new byte[readers.size()][size];
                        for(int i=0;i<readers.size();i++)readFully(readers.get(i),chunks[i],round*block);
                        for(int r=0;r<n;r++){
                            check(cancel);
                            for(int index:indices){if(reverse){for(int j=b-1;j>=0;j--)out.write(chunks[index][r*b+j]);}else out.write(chunks[index],r*b,b);}
                        }
                        round+=n;written+=((long)n)*block*indices.length;progress.update(written,total);
                    }
                }else{
                    byte[] bytes=new byte[BUFFER];
                    for(long round=0;round<rounds;round++)for(int index:indices){
                        for(long pos=0;pos<block;){
                            check(cancel);int length=(int)Math.min(bytes.length,block-pos);
                            long source=round*block+(reverse?block-pos-length:pos);
                            readFully(readers.get(index),bytes,length,source);
                            if(reverse)for(int a=0,z=length-1;a<z;a++,z--){byte v=bytes[a];bytes[a]=bytes[z];bytes[z]=v;}
                            out.write(bytes,0,length);written+=length;pos+=length;progress.update(written,total);
                        }
                    }
                }
            }
            check(cancel);
            for(int i=0;i<paths.size();i++){
                BasicFileAttributes now=Files.readAttributes(paths.get(i),BasicFileAttributes.class),old=before.get(i);
                if(now.size()!=old.size()||!now.lastModifiedTime().equals(old.lastModifiedTime())||!Objects.equals(now.fileKey(),old.fileKey()))throw new IOException("Eingabedatei wurde während der Verarbeitung verändert: "+paths.get(i));
            }
            rejectAliases(paths,target);check(cancel);
            if(overwrite){try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}}
            else Files.move(temporary,target);
            temporary=null;return written;
        }finally{
            IOException failure=null;
            for(FileChannel reader:readers)try{reader.close();}catch(IOException ex){failure=ex;}
            if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ex){failure=ex;}
            if(failure!=null)throw failure;
        }
    }
    private static void rejectAliases(List<Path> paths,Path output)throws IOException {
        for(Path p:paths)if(p.equals(output)||(Files.exists(output)&&Files.isSameFile(p,output)))throw new IOException("Die Ausgabe darf keine Eingabedatei überschreiben.");
    }
    private static void check(BooleanSupplier cancelled)throws InterruptedException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException("MergeFiles abgebrochen; Ziel unverändert.");}
    private static void readFully(FileChannel channel,byte[] data,long position)throws IOException {readFully(channel,data,data.length,position);}
    private static void readFully(FileChannel channel,byte[] data,int length,long position)throws IOException {
        ByteBuffer b=ByteBuffer.wrap(data,0,length);while(b.hasRemaining()){int n=channel.read(b,position+b.position());if(n<0)throw new EOFException("Eingabedatei wurde verkürzt.");if(n==0)throw new IOException("Eingabedatei kann nicht weiter gelesen werden.");}
    }
    static byte[] preview(Path path,long offset,long block,boolean afterSwap,boolean reverse)throws IOException {
        if(offset<0||block<1)throw new IllegalArgumentException("Offset muss mindestens 0, Blockgröße mindestens 1 sein.");
        try(FileChannel in=FileChannel.open(path,StandardOpenOption.READ)){
            long end=afterSwap?(in.size()/block)*block:in.size();
            int size=(int)Math.min(256,Math.max(0,end-offset));byte[] bytes=new byte[size];
            if(afterSwap&&reverse){byte[] single=new byte[1];for(int i=0;i<size;i++){long pos=offset+i;readFully(in,single,(pos/block)*block+block-1-pos%block);bytes[i]=single[0];}}
            else if(size>0)readFully(in,bytes,offset);
            return bytes;
        }
    }
}
