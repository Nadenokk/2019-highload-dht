package ru.mail.polis.service.serviced;
import com.google.common.base.Charsets;
import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

public final class ServiceRestHttp extends HttpServer implements Service {

}
