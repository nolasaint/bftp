/**
 * Basic File Transfer Protocol
 *
 * @TODO Write class-level documentation
 *
 * @author  Evan Bailey
 * @version 1.0
 * @since   2015-11-30
 */
package net.nolasaint.bftp;

@SuppressWarnings("unused")
public class BFTP {

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
    public static final byte GET_RSP = 0b00001001; // GET, RSP bits set
    public static final byte PUT_RSP = 0b00001010; // PUT, RSP bits set
    public static final byte FIN_RSP = 0b00001100; // FIN, RSP bits set [optional]
    public static final byte GET_ERR = 0b00010001; // ERR, GET bits set (error response to GET)
    public static final byte PUT_ERR = 0b00010010; // ERR, PUT bits set (error response to PUT)
    public static final byte FIN_ERR = 0b00010100; // ERR, FIN bits set
    public static final byte RSP_ERR = 0b00011000; // ERR, RSP bits set [optional]

    // @TODO large files broken into partial messages, must have flag
    // @TODO If we want parallelism, must distinguish parts from each connection
    // @TODO Or just have # incoming segments in generic RSP packet
}
