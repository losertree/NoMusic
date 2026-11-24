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
 * NoMusic modification: use readFully instead of assuming a channel fills a
 * buffer; validate lengths and sparse indices. Preserve the upstream format.
 */
package com.atilika.kuromoji.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class IntegerArrayIO {
    private IntegerArrayIO(){}
    private static int length(DataInputStream input)throws IOException{int value=input.readInt();if(value<0||value>32*1024*1024)throw new IOException("Invalid dictionary array length");return value;}
    public static int[] readArray(InputStream input)throws IOException{
        DataInputStream data=new DataInputStream(input);int size=length(data);
        ByteBuffer buffer=ByteBuffer.allocate(size*4);data.readFully(buffer.array());int[] values=new int[size];buffer.asIntBuffer().get(values);return values;
    }
    public static void writeArray(OutputStream output,int[] values)throws IOException{DataOutputStream data=new DataOutputStream(output);data.writeInt(values.length);for(int value:values)data.writeInt(value);}
    public static int[][] readArray2D(InputStream input)throws IOException{
        DataInputStream data=new DataInputStream(input);int[][] values=new int[length(data)][];for(int i=0;i<values.length;i++)values[i]=readArray(data);return values;
    }
    public static void writeArray2D(OutputStream output,int[][] values)throws IOException{DataOutputStream data=new DataOutputStream(output);data.writeInt(values.length);for(int[] value:values)writeArray(data,value);}
    public static int[][] readSparseArray2D(InputStream input)throws IOException{
        DataInputStream data=new DataInputStream(input);int[][] values=new int[length(data)][];int index;
        while((index=data.readInt())>=0){if(index>=values.length)throw new IOException("Invalid dictionary array index");values[index]=readArray(data);}return values;
    }
    public static void writeSparseArray2D(OutputStream output,int[][] values)throws IOException{DataOutputStream data=new DataOutputStream(output);data.writeInt(values.length);for(int i=0;i<values.length;i++)if(values[i]!=null){data.writeInt(i);writeArray(data,values[i]);}data.writeInt(-1);}
}
