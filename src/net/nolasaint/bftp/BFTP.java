package net.nolasaint.bftp;

/**
 * Basic File Transfer Protocol
 *
 * TODO: Write class-level documentation
 *
 * Created: 2015-11-30
 * @author  Evan Bailey
 * @version 1.0
 */
@SuppressWarnings("unused")
public final class BFTP {

    /* METADATA */
    public static final String VERSION = "1.0";

    /* FIELD SIZES */
    public static final int CSIZE_LENGTH  = 4; // in bytes ... length of content size field
    public static final int OPCODE_LENGTH = 1; // in bytes ... length of opcode field
    public static final int HEADER_LENGTH = CSIZE_LENGTH + OPCODE_LENGTH;

    /* OPCODES */
    //                                 0b000ERFPG ... Philosophy
    public static final byte GET     = 0b00000001; // GET bit set
    public static final byte PUT     = 0b00000010; // PUT bit set
    public static final byte FIN     = 0b00000100; // FIN bit set
    public static final byte RSP     = 0b00001000; // RSP bit set
    public static final byte ERR     = 0b00010000; // ERR bit set

}
