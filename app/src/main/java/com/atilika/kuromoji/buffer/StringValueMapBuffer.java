/**
 * Copyright 2010-2015 Atilika Inc. and contributors (see CONTRIBUTORS.md)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NoMusic modification: Charset.forName("UTF-8") replaces StandardCharsets,
 * which is unavailable on Android API 16-18. The dictionary format is unchanged.
 */
package com.atilika.kuromoji.buffer;

import com.atilika.kuromoji.io.ByteBufferIO;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.TreeMap;

public class StringValueMapBuffer {
    private static final int INTEGER_BYTES=Integer.SIZE/Byte.SIZE;
    private static final int SHORT_BYTES=Short.SIZE/Byte.SIZE;
    private static final Charset UTF8=Charset.forName("UTF-8");
    private ByteBuffer buffer;

    public StringValueMapBuffer(TreeMap<Integer,String> features){putMap(features);}
    public StringValueMapBuffer(InputStream input)throws IOException{buffer=ByteBufferIO.read(input);}
    private static int getMetaDataSize(){return INTEGER_BYTES;}
    public void putMap(TreeMap<Integer,String> input){
        buffer=ByteBuffer.wrap(new byte[calculateSize(input)+getMetaDataSize()]);
        buffer.putInt(input.size());int position=getMetaDataSize(),address=position+input.size()*INTEGER_BYTES;
        for(Integer index:input.keySet()){buffer.putInt(position,address);address=putString(address,input.get(index));position+=INTEGER_BYTES;}
    }
    private int calculateSize(TreeMap<Integer,String> input){int size=0;for(String value:input.values())size+=INTEGER_BYTES+value.getBytes(UTF8).length+2*INTEGER_BYTES;return size;}
    private int putString(int address,String value){
        byte[] bytes=value.getBytes(UTF8);buffer.position(address);buffer.putShort((short)bytes.length);buffer.put(bytes);return address+SHORT_BYTES+bytes.length;
    }
    public String get(int index){return getString(buffer.getInt(index*INTEGER_BYTES+getMetaDataSize()));}
    private String getString(int address){int length=buffer.getShort(address);return new String(buffer.array(),address+SHORT_BYTES,length,UTF8);}
    public void write(OutputStream output)throws IOException{ByteBufferIO.write(output,buffer);}
}
