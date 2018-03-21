package rpc.turbo.serialization.protostuff;

import static rpc.turbo.util.concurrent.AttachmentThreadUtils.getOrUpdate;
import static rpc.turbo.util.concurrent.AttachmentThreadUtils.nextVarIndex;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.protostuff.Schema;
import io.protostuff.runtime.DefaultIdStrategy;
import io.protostuff.runtime.Delegate;
import io.protostuff.runtime.IdStrategy;
import io.protostuff.runtime.RuntimeSchema;
import rpc.turbo.config.TurboConstants;
import rpc.turbo.param.EmptyMethodParam;
import rpc.turbo.param.MethodParam;
import rpc.turbo.protocol.Request;
import rpc.turbo.protocol.Response;
import rpc.turbo.protocol.ResponseStatus;
import rpc.turbo.protocol.recycle.RecycleRequest;
import rpc.turbo.protocol.recycle.RecycleResponse;
import rpc.turbo.protocol.recycle.RecycleUtils;
import rpc.turbo.serialization.Serializer;
import rpc.turbo.serialization.TracerSerializer;
import rpc.turbo.trace.Tracer;
import rpc.turbo.util.ByteBufUtils;
import rpc.turbo.util.concurrent.ConcurrentIntToObjectArrayMap;

public class ProtostuffSerializer extends Serializer {

	/** 客户端服务端通用 */
	private static final ConcurrentHashMap<Class<? extends MethodParam>, Schema<MethodParam>> schemaMap//
			= new ConcurrentHashMap<>(32, 0.5F);

	/** 服务端使用 */
	private static final ConcurrentIntToObjectArrayMap<Schema<MethodParam>> fastServerSchemaMap//
			= new ConcurrentIntToObjectArrayMap<>();

	private static final Schema<EmptyMethodParam> emptyMethodParamSchema//
			= RuntimeSchema.getSchema(EmptyMethodParam.class);

	private static final Schema<Response> responseSchema //
			= RuntimeSchema.getSchema(Response.class, createIdStrategy(new TracerDelegate()));

	private static final TracerSerializer tracerSerializer = new TracerSerializer();

	private static final int OUTPUT_ATTACHMENT_INDEX = nextVarIndex();
	private static final int INPUT_ATTACHMENT_INDEX = nextVarIndex();

	private static final Supplier<ByteBufOutput> OUTPUT_SUPPLIER = () -> new ByteBufOutput(null);
	private static final Supplier<ByteBufInput> INPUT_SUPPLIER = () -> new ByteBufInput(null, true);

	public void writeRequest(ByteBuf byteBuf, Request request) throws IOException {
		final int beginWriterIndex = byteBuf.writerIndex();

		byteBuf.writerIndex(beginWriterIndex + TurboConstants.HEADER_FIELD_LENGTH);
		byteBuf.writeInt(request.getRequestId());
		ByteBufUtils.writeVarInt(byteBuf, request.getServiceId());
		tracerSerializer.write(byteBuf, request.getTracer());

		ByteBufOutput output = getOrUpdate(OUTPUT_ATTACHMENT_INDEX, OUTPUT_SUPPLIER);
		output.setByteBuf(byteBuf);

		if (request.getMethodParam() == null) {
			emptyMethodParamSchema.writeTo(output, null);
		} else {
			// serviceId 为服务端概念，相同的方法在不同的服务端 serviceId 会不相同，所以没法直接使用 serviceId 获取 schema
			// 如果能够证明这里比较慢，可以考虑在 request 中加入 methodId，这个在客户端是唯一的
			Class<? extends MethodParam> clazz = request.getMethodParam().getClass();
			Schema<MethodParam> schema = schema(clazz);

			schema.writeTo(output, request.getMethodParam());
		}

		int finishWriterIndex = byteBuf.writerIndex();
		int length = finishWriterIndex - beginWriterIndex - TurboConstants.HEADER_FIELD_LENGTH;

		byteBuf.setInt(beginWriterIndex, length);

		RecycleUtils.release(request);
	}

	public Request readRequest(ByteBuf byteBuf) throws IOException {
		int requestId = byteBuf.readInt();
		int serviceId = ByteBufUtils.readVarInt(byteBuf);
		Tracer tracer = tracerSerializer.read(byteBuf);

		Schema<MethodParam> schema = schema(serviceId);

		ByteBufInput input = getOrUpdate(INPUT_ATTACHMENT_INDEX, INPUT_SUPPLIER);
		input.setByteBuf(byteBuf, true);

		MethodParam methodParam = schema.newMessage();

		schema.mergeFrom(input, methodParam);

		Request request = RecycleRequest.newInstance(requestId, serviceId, tracer, methodParam);

		return request;
	}

	public void writeResponse(ByteBuf byteBuf, Response response) throws IOException {
		int beginWriterIndex = byteBuf.writerIndex();
		byteBuf.writerIndex(beginWriterIndex + TurboConstants.HEADER_FIELD_LENGTH);

		ByteBufOutput output = getOrUpdate(OUTPUT_ATTACHMENT_INDEX, OUTPUT_SUPPLIER);
		output.setByteBuf(byteBuf);

		final int statusWriterIndex = byteBuf.writerIndex();

		try {
			responseSchema.writeTo(output, response);
		} catch (Exception e) {
			e.printStackTrace();

			response.setStatusCode(ResponseStatus.BAD_RESPONSE);
			response.setResult(e.getMessage());

			byteBuf.writerIndex(statusWriterIndex);
			responseSchema.writeTo(output, response);
		}

		int finishWriterIndex = byteBuf.writerIndex();
		int length = finishWriterIndex - beginWriterIndex - TurboConstants.HEADER_FIELD_LENGTH;

		byteBuf.setInt(beginWriterIndex, length);

		RecycleUtils.release(response);
	}

	public Response readResponse(ByteBuf byteBuf) throws IOException {
		ByteBufInput input = getOrUpdate(INPUT_ATTACHMENT_INDEX, INPUT_SUPPLIER);
		input.setByteBuf(byteBuf, true);

		Response response = RecycleResponse.newInstance(0, (byte) 0, null, null);
		responseSchema.mergeFrom(input, response);

		return response;
	}

	/**
	 * 客户端或者服务端获取ProtostuffSchema，该方法速度慢
	 * 
	 * @param clazz
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Schema<MethodParam> schema(Class<? extends MethodParam> clazz) {
		Schema<MethodParam> schema = schemaMap.get(clazz);

		if (schema != null) {
			return schema;
		}

		schema = schemaMap.computeIfAbsent(clazz, k -> (Schema<MethodParam>) RuntimeSchema.getSchema(clazz));
		return schema;
	}

	/**
	 * 服务端获取ProtostuffSchema，该方法速度快
	 * 
	 * @param serviceId
	 * @return
	 */
	private Schema<MethodParam> schema(int serviceId) {
		Schema<MethodParam> schema = fastServerSchemaMap.get(serviceId);

		if (schema != null) {
			return schema;
		}

		schema = fastServerSchemaMap.getOrUpdate(serviceId, () -> schema(getClass(serviceId)));

		return schema;
	}

	private static IdStrategy createIdStrategy(Delegate<?>... delegates) {
		DefaultIdStrategy idStrategy = new DefaultIdStrategy();

		for (int i = 0; i < delegates.length; i++) {
			Delegate<?> delegate = delegates[i];
			idStrategy.registerDelegate(delegate);
		}

		return idStrategy;
	}

}