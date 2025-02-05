import React from "react";

export interface DeleteItemProps {
    canDelete: boolean;
}
export const DeleteItem = ({canDelete}: DeleteItemProps) => {
    return (
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path
                fillRule="evenodd"
                clipRule="evenodd"
                d="M6.5 1.75C6.5 1.61193 6.61193 1.5 6.75 1.5H9.25C9.38807 1.5 9.5 1.61193 9.5 1.75V3H6.5V1.75ZM11 1.75V3H13.25C13.6642 3 14 3.33579 14 3.75C14 4.16421 13.6642 4.5 13.25 4.5H2.75C2.33579 4.5 2 4.16421 2 3.75C2 3.33579 2.33579 3 2.75 3H5V1.75C5 0.783502 5.7835 0 6.75 0H9.25C10.2165 0 11 0.783502 11 1.75ZM4.49627 6.67537C4.45506 6.26321 4.08753 5.9625 3.67537 6.00372C3.26321 6.04493 2.9625 6.41247 3.00372 6.82462L3.66367 13.4241C3.75313 14.3187 4.50592 15 5.40498 15H10.595C11.4941 15 12.2469 14.3187 12.3363 13.4241L12.9963 6.82462C13.0375 6.41247 12.7368 6.04493 12.3246 6.00372C11.9125 5.9625 11.5449 6.26321 11.5037 6.67537L10.8438 13.2749C10.831 13.4027 10.7234 13.5 10.595 13.5H5.40498C5.27655 13.5 5.169 13.4027 5.15622 13.2749L4.49627 6.67537Z"
                fill={canDelete ? "#EE3A1B" : "#CCCCCC"}
            />
        </svg>
    );
};

export const ViewIcon = () => {
    return (
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
            <g id="eye-16">
                <path
                    id="Vector"
                    fillRule="evenodd"
                    clipRule="evenodd"
                    d="M1.67884 7.93165C2.09143 7.31094 2.9206 6.18152 4.0447 5.21477C5.17567 4.2421 6.52738 3.5 8.00001 3.5C9.47264 3.5 10.8244 4.2421 11.9553 5.21477C13.0794 6.18152 13.9086 7.31094 14.3212 7.93165C14.35 7.975 14.35 8.025 14.3212 8.06835C13.9086 8.68906 13.0794 9.81848 11.9553 10.7852C10.8244 11.7579 9.47264 12.5 8.00001 12.5C6.52738 12.5 5.17567 11.7579 4.0447 10.7852C2.9206 9.81848 2.09143 8.68906 1.67884 8.06835C1.65002 8.025 1.65002 7.975 1.67884 7.93165ZM8.00001 2C6.01865 2 4.32919 2.99167 3.06662 4.07751C1.79718 5.16926 0.880454 6.42307 0.429635 7.10129C0.0664231 7.64771 0.0664245 8.35229 0.429635 8.89871C0.880455 9.57693 1.79718 10.8307 3.06662 11.9225C4.32919 13.0083 6.01865 14 8.00001 14C9.98137 14 11.6708 13.0083 12.9334 11.9225C14.2028 10.8307 15.1196 9.57693 15.5704 8.89871C15.9336 8.35229 15.9336 7.64771 15.5704 7.10129C15.1196 6.42307 14.2028 5.16926 12.9334 4.07751C11.6708 2.99167 9.98137 2 8.00001 2ZM8 10C9.10457 10 10 9.10457 10 8C10 6.89543 9.10457 6 8 6C6.89543 6 6 6.89543 6 8C6 9.10457 6.89543 10 8 10Z"
                    fill="#6C6C6C"
                />
            </g>
        </svg>
    );
};
