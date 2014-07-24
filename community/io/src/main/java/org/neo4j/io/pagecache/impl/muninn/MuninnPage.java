/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache.impl.muninn;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.StampedLock;

import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.pagecache.Page;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PageSwapper;

class MuninnPage extends StampedLock implements Page
{
    private static final Constructor<?> directBufferConstructor;
    private static final long usageStampOffset = UnsafeUtil.getFieldOffset( MuninnPage.class, "usageStamp" );
    static {
        Constructor<?> ctor = null;
        try
        {
            Class<?> dbb = Class.forName( "java.nio.DirectByteBuffer" );
            ctor = dbb.getDeclaredConstructor( Long.TYPE, Integer.TYPE );
            ctor.setAccessible( true );
        }
        catch ( Exception e )
        {
            e.printStackTrace();
        }
        directBufferConstructor = ctor;
    }

    final int cachePageId;
    final int cachePageSize;
    private long pointer;
    private ByteBuffer bufferProxy;

    // Optimistically incremented; occasionally truncated to a max of 5.
    // accessed through unsafe
    private volatile int usageStamp;
    // Next pointer in the freelist of available pages
    public volatile MuninnPage nextFree;

    private PageSwapper swapper;
    private long filePageId = PageCursor.UNBOUND_PAGE_ID;
    private boolean dirty;

    public MuninnPage( int cachePageId, int cachePageSize )
    {
        this.cachePageId = cachePageId;
        this.cachePageSize = cachePageSize;
    }

    public byte getByte( int offset )
    {
        return UnsafeUtil.getByte( pointer + offset );
    }

    public void putByte( byte value, int offset )
    {
        UnsafeUtil.putByte( pointer + offset, value );
    }

    public long getLong( int offset )
    {
        return UnsafeUtil.getLong( pointer + offset );
    }

    public void putLong( long value, int offset )
    {
        UnsafeUtil.putLong( pointer + offset, value );
    }

    public int getInt( int offset )
    {
        return UnsafeUtil.getInt( pointer + offset );
    }

    public void putInt( int value, int offset )
    {
        UnsafeUtil.putInt( pointer + offset, value );
    }

    @Override
    public void getBytes( byte[] data, int offset )
    {
        long address = pointer + offset;
        for ( int i = 0; i < data.length; i++ )
        {
            data[i] = UnsafeUtil.getByte( address );
            address++;
        }
    }

    @Override
    public void putBytes( byte[] data, int offset )
    {
        long address = pointer + offset;
        for ( int i = 0; i < data.length; i++ )
        {
            UnsafeUtil.putByte( address, data[i] );
            address++;
        }
    }

    public short getShort( int offset )
    {
        return UnsafeUtil.getShort( pointer + offset );
    }

    public void putShort( short value, int offset )
    {
        UnsafeUtil.putShort( pointer + offset, value );
    }

    /** Increment the usage stamp to at most 5. */
    public void incrementUsage()
    {
        if ( usageStamp < 5 )
        {
            UnsafeUtil.getAndAddInt( this, usageStampOffset, 1 );
        }
    }

    /** Decrement the usage stamp. Returns true if it reaches 0. */
    public boolean decrementUsage()
    {
        if ( usageStamp > 0 )
        {
            return UnsafeUtil.getAndAddInt( this, usageStampOffset, -1 ) <= 1;
        }
        return true;
    }

    /**
     * NOTE: This method must be called while holding the page write lock.
     * This method assumes that initBuffer() has already been called at least once.
     */
    @Override
    public void swapIn( StoreChannel channel, long offset, int length ) throws IOException
    {
        assert isWriteLocked() : "swapIn requires write lock";
        bufferProxy.clear();
        bufferProxy.limit( length );
        int readTotal = 0;
        int read;
        do
        {
            read = channel.read( bufferProxy, offset + readTotal );
        }
        while ( read != -1 && (readTotal += read) < length );

        // Zero-fill the rest.
        while ( bufferProxy.position() < bufferProxy.limit() )
        {
            bufferProxy.put( (byte) 0 );
        }
    }

    /**
     * NOTE: This method must be called while holding at least the page read lock.
     * This method assumes that initBuffer() has already been called at least once.
     */
    @Override
    public void swapOut( StoreChannel channel, long offset, int length ) throws IOException
    {
        assert isWriteLocked() : "swapOut requires write lock";
        bufferProxy.clear();
        bufferProxy.limit( length );
        channel.writeAll( bufferProxy );
    }

    /**
     * NOTE: This method must be called while holding the page write lock.
     */
    public void flush() throws IOException
    {
        if ( swapper != null && dirty )
        {
            // The page is bound and has stuff to flush
            swapper.write( filePageId, this );
            dirty = false;
        }
    }

    /**
     * NOTE: This method must be called while holding the page write lock.
     */
    public void flush( PageSwapper swapper ) throws IOException
    {
        if ( this.swapper == swapper && dirty )
        {
            // The page is bound to the given swapper and has stuff to flush
            swapper.write( filePageId, this );
            dirty = false;
        }
    }

    public void markAsDirty()
    {
        dirty = true;
    }

    /**
     * NOTE: This method MUST be called while holding the page write lock.
     */
    public void fault(
            PageSwapper swapper,
            long filePageId ) throws IOException
    {
        if ( this.swapper != null || this.filePageId != PageCursor.UNBOUND_PAGE_ID )
        {
            throw new IllegalStateException( "Cannot fault on bound page" );
        }
        this.swapper = swapper;
        this.filePageId = filePageId;
        swapper.read( filePageId, this );
    }

    /**
     * NOTE: This method MUST be called while holding the page write lock.
     */
    public void evict() throws IOException
    {
        flush();
        filePageId = PageCursor.UNBOUND_PAGE_ID;
        swapper = null;
    }

    public boolean isLoaded()
    {
        return filePageId != PageCursor.UNBOUND_PAGE_ID;
    }

    public boolean pin( PageSwapper swapper, long filePageId )
    {
        return this.swapper == swapper && this.filePageId == filePageId;
    }

    /**
     * NOTE: This method MUST be called while holding the page write lock.
     */
    public void initBuffer()
    {
        if ( bufferProxy == null )
        {
            pointer = UnsafeUtil.malloc( cachePageSize );
            try
            {
                bufferProxy = (ByteBuffer) directBufferConstructor.newInstance(
                        pointer, cachePageSize );
            }
            catch ( Exception e )
            {
                throw new AssertionError( e );
            }
        }
    }

    /**
     * NOTE: This method MUST be called while holding the page write lock,
     * AND it must be guaranteed that no other threads are concurrently
     * accessing the page, e.g. via optimistic read.
     */
    public void freeBuffer()
    {
        bufferProxy = null;
        UnsafeUtil.free( pointer );
    }

    public PageSwapper getSwapper()
    {
        return swapper;
    }

    public long getFilePageId()
    {
        return filePageId;
    }
}
