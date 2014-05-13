import socket
import time
import picamera
import sys

# Connect a client socket to my_server:8000 (change my_server to the
# hostname of your server)
if (len(sys.argv) < 2):
    print "DID NOT ENTER HOSTNAME of SERVER"
    sys.exit(-1)

HOSTNAME = sys.argv[1]
client_socket = socket.socket()
client_socket.connect(('my_server', 8000))

# Make a file-like object out of the connection
connection = client_socket.makefile('wb')
try:
    with picamera.PiCamera() as camera:
        camera.resolution = (640, 480)
        # Start a preview and let the camera warm up for 2 seconds
        camera.start_preview()
        time.sleep(2)
        # Start recording, sending the output to the connection for 60
        # seconds, then stop
        camera.start_recording(connection, format='h264')
        camera.wait_recording(60)
        camera.stop_recording()
finally:
        connection.close()
        client_socket.close()
