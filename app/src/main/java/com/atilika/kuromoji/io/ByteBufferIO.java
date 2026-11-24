/*
 * Copyright 2010-2015 Atilika Inc. and contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NoMusic modification: read every byte on Android channels that return short
 * reads; reject invalid sizes. Public upstream method signatures are retained.
 */
package com.atilika.kuromoji.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class ByteBufferIO {
    private ByteBufferIO(){}
    public static ByteBuffer read(InputStream input)throws IOException{
        DataInputStream data=new DataInputStream(input);int size=data.readInt();
        if(size<0||size>128*1024*1024)throw new IOException("Invalid dictionary buffer size");
        ByteBuffer buffer=ByteBuffer.allocate(size);data.readFully(buffer.array());return buffer;
    }
    public static void write(OutputStream output,ByteBuffer buffer)throws IOException{
        DataOutputStream data=new DataOutputStream(output);data.writeInt(buffer.position());buffer.flip();
        byte[] bytes=new byte[8192];while(buffer.hasRemaining()){int length=Math.min(bytes.length,buffer.remaining());buffer.get(bytes,0,length);data.write(bytes,0,length);}data.flush();
    }
}
