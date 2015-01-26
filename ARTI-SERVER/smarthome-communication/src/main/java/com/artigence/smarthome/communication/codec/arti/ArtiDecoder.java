package com.artigence.smarthome.communication.codec.arti;

import com.artigence.smarthome.communication.codec.Decoder;
import com.artigence.smarthome.communication.codec.arithmetic.CheckSum;
import com.artigence.smarthome.communication.core.BufferContext;
import com.artigence.smarthome.communication.protocol.ArtiProtocol;
import com.artigence.smarthome.communication.session.CID;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * 
 * @author ripon 协议： 字段 大小 destination 2 length 2 crc 2 data >2 crc 2
 */
@Component
public class ArtiDecoder extends CumulativeProtocolDecoder implements Decoder {
	private static final Log log = LogFactory.getLog(ArtiDecoder.class);
	private final AttributeKey CONTEXT = new AttributeKey(getClass(), "context");
	private final CheckSum check = new CheckSum();
	private int maxPackLength = 2000;



	public BufferContext getContext(IoSession session) {
		BufferContext ctx;
		ctx = (BufferContext) session.getAttribute(CONTEXT);
		if (ctx == null) {
			ctx = new BufferContext();
			session.setAttribute(CONTEXT, ctx);
		}
		return ctx;
	}

	@Override
	public void dispose(IoSession session) throws Exception {
		BufferContext ctx = (BufferContext) session.getAttribute(CONTEXT);
		if (ctx != null) {
			session.removeAttribute(CONTEXT);
		}
	}

	@Override
	protected boolean doDecode(IoSession session, IoBuffer in,
			ProtocolDecoderOutput out) throws Exception {
		return decoderProtocol(session, in, out);
	}

	private boolean decoderProtocol(IoSession session, IoBuffer in,
			ProtocolDecoderOutput out) throws Exception {

		final int packHeadLength = ArtiProtocol.PROTOCOL_HEAD_LENGTH;
		// 先获取上次的处理上下文，其中可能有未处理完的数据
		BufferContext ctx = getContext(session);
		// 先把当前buffer中的数据追加到Context的buffer当中
		log.info("session-"+session.getId()+
				"limit[" + in.limit() + "]:"+Hex.encodeHexString(in.array()).substring(in.position()*2, in.limit()*2));
		ctx.append(in);
		// 把position指向0位置，把limit指向原来的position位置
		IoBuffer buf = ctx.getBuffer();
		buf.flip();
		// 然后按数据包的协议进行读取
		while (buf.remaining() >= packHeadLength) {
			buf.mark();
			// 读取消息头部分
			int length = buf.remaining();
			log.info("session-"+session.getId()+" 接收未解码数据长度[" + length + "] "+
					"limit[" + buf.limit() + "]:"+Hex.encodeHexString(buf.array()).substring(buf.position()*2, buf.limit()*2));
			// 检查读取是否正常，不正常的话清空buffer
			if (length < 0 || length > maxPackLength) {
				System.out.println("长度[" + length
						+ "] > maxPackLength or <0....");
				buf.clear();
				break;
			}
			// 读取正常的消息，并写入输出流中，以便IoHandler进行处理
			else if (length >= packHeadLength) {

				//header bytes
				byte[] headerbytes = new byte[packHeadLength-2];
				buf.get(headerbytes);
				buf.reset();

				//source | destination
				String source = buf.getString(32, Charset.forName("UTF-8").newDecoder());
				String destination = buf.getString(32, Charset.forName("UTF-8").newDecoder());
				CID sourceId = new CID(source);
				CID destinationId = new CID(destination);

				//length | header crc
				int bodyLength = buf.getChar();
				int headCrc = buf.getChar();

				//checksum header
				if(!this.checkHeader(headerbytes, headCrc)){
					log.info("protocol header checkSum error");
					session.write(ArtiProtocol.getValidResult(false,sourceId,destinationId));
					buf.clear();
					buf.limit(buf.position());
					break;
				}

				//body
				if (bodyLength >= 2 && (bodyLength+2) <= buf.remaining()) {
					byte[] data = new byte[bodyLength];
					buf.get(data);
					int bodyCrc = buf.getChar();
					
					if(!this.checkBody(data, bodyCrc)){
						log.info("protocol body checkSum error");
						session.write(ArtiProtocol.getValidResult(false,sourceId,destinationId));
						buf.clear();
						buf.limit(buf.position());
						break;						
					}

					ArtiProtocol artiProtocol = new ArtiProtocol(sourceId, destinationId,
							null,data, data.length, headCrc, bodyCrc);
					out.write(artiProtocol);
				}else if(bodyLength < 4){
					log.info("protocol body length < 4 is error");
					buf.clear();buf.limit(buf.position());
					break;	
				}else {
					// 如果消息包不完整
					// 将指针重新移动消息头的起始位置
					buf.reset();
					break;
				}
			} else {
				// 如果消息包不完整
				// 将指针重新移动消息头的起始位置
				buf.reset();
				break;
			}
		}
		if (buf.hasRemaining()) {
			// 将数据移到buffer的最前面
			IoBuffer temp = IoBuffer.allocate(maxPackLength)
					.setAutoExpand(true);
			temp.put(buf);
			temp.flip();
			buf.clear();
			buf.put(temp);

		} else {// 如果数据已经处理完毕，进行清空
			buf.clear();
		}
		return true;
	}
	
	private boolean checkHeader(byte[] header,long headCrc){
		if(check.check(header) != headCrc)
			return false;
		return true;
	}
	
	private boolean checkBody(byte[] body,long bodyCrc){
		if(check.check(body) != bodyCrc)
			return false;
		return true;
	}


	public int getMaxLineLength() {
		return maxPackLength;
	}

	public void setMaxLineLength(int maxLineLength) {
		if (maxLineLength <= 0) {
			throw new IllegalArgumentException("maxLineLength: "
					+ maxLineLength);
		}
		this.maxPackLength = maxLineLength;
	}
}
