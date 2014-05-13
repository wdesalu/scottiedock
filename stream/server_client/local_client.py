""" This is the code for the client RasPi that outputs to a camera when it receives a signal. """

import socket, thread

def read_stdin(s):
    while (True):
        var = raw_input()
        if (var == '0' or var == '1'):
            s.sendall(var) 
        return

def photo_handler():
    """ Function called when Pi receives a photo signal. CHANGE THIS """
    print "Take a photo"

def video_handler():
    """ Function called when Pi receives a video signal. CHANGE THIS """
    print "Take a video"


HOST = 'unix4.andrew.cmu.edu'   # The remote host
PORT = 5000                     # The same port as used by the server

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect((HOST, PORT))
count = 0
thread.start_new_thread(read_stdin, (s,))
while (True):
    #s.sendall('Hello, world')
    data = s.recv(1024)
    if (data == '0'):
        photo_handler()
    elif (data == '1'):
        video_handler()
s.close()
